package com.mariaalpha.marketdatagateway.adapter.alpaca;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import com.mariaalpha.marketdatagateway.adapter.alpaca.message.AlpacaAuthStatus;
import com.mariaalpha.marketdatagateway.adapter.alpaca.message.AlpacaBarsResponse;
import com.mariaalpha.marketdatagateway.adapter.alpaca.message.AlpacaControl;
import com.mariaalpha.marketdatagateway.adapter.alpaca.message.AlpacaMessageType;
import com.mariaalpha.marketdatagateway.adapter.alpaca.message.AlpacaQuote;
import com.mariaalpha.marketdatagateway.adapter.alpaca.message.AlpacaTrade;
import com.mariaalpha.marketdatagateway.config.AlpacaMarketDataConfig;
import com.mariaalpha.marketdatagateway.model.BarTimeframe;
import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.EventType;
import com.mariaalpha.marketdatagateway.model.HistoricalBar;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
@Profile("alpaca")
public class AlpacaMarketDataAdapter implements MarketDataAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(AlpacaMarketDataAdapter.class);
  private static final int PAGE_LIMIT = 10000;

  private final AlpacaMarketDataConfig config;
  private final ObjectMapper objectMapper;
  private final Sinks.Many<MarketTick> tickSink;
  private volatile Disposable wsConnection;
  private final WebClient webClient;

  public AlpacaMarketDataAdapter(AlpacaMarketDataConfig config) {
    this.config = config;
    this.objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    // bridge imperative (WebSocket callback pushes data) to reactive (downstream consumers
    // subscribe to a Flux)
    this.tickSink = Sinks.many().multicast().onBackpressureBuffer();
    this.webClient =
        WebClient.builder()
            .baseUrl(config.baseUrl())
            .defaultHeader("APCA-API-KEY-ID", config.apiKeyId())
            .defaultHeader("APCA-API-SECRET-KEY", config.apiSecretKey())
            .build();
  }

  @Override
  public void connect(List<String> symbols) {
    var client = new ReactorNettyWebSocketClient();
    var uri = URI.create(config.websocketUrl());

    wsConnection =
        client
            .execute(
                uri,
                session ->
                    session
                        .receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(msg -> handleMessage(msg, session, symbols))
                        .then())
            .doOnError(err -> LOG.error("WebSocket error", err))
            .doOnTerminate(() -> LOG.info("WebSocket connection closed"))
            .subscribe();
    LOG.info("Connecting to Alpaca WebSocket at {}", uri);
  }

  @Override
  public void disconnect() {
    if (wsConnection != null && !wsConnection.isDisposed()) {
      wsConnection.dispose();
    }
    tickSink.tryEmitComplete();
    LOG.info("Disconnected from Alpaca WebSocket");
  }

  @Override
  public Flux<MarketTick> streamTicks() {
    return tickSink.asFlux();
  }

  @Override
  public List<HistoricalBar> getHistoricalBars(
      String symbol, LocalDate from, LocalDate to, BarTimeframe timeframe) {
    var allBars = new ArrayList<HistoricalBar>();
    String pageToken = null;

    do {
      final String currentToken = pageToken;
      var response =
          webClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/v2/stocks/{symbol}/bars")
                          .queryParam("timeframe", timeframe.getLabel())
                          .queryParam("start", from.toString())
                          .queryParam("end", to.toString())
                          .queryParam("limit", PAGE_LIMIT)
                          .queryParam("adjustment", "raw")
                          .queryParam("feed", "iex")
                          .queryParamIfPresent(
                              "page_token",
                              currentToken == null ? Optional.empty() : Optional.of(currentToken))
                          .build(symbol))
              .retrieve()
              .bodyToMono(AlpacaBarsResponse.class)
              .block();

      if (response != null) {
        for (var bar : response.bars()) {
          allBars.add(
              new HistoricalBar(
                  symbol,
                  bar.timestamp().atZone(ZoneOffset.UTC).toLocalDate(),
                  bar.open(),
                  bar.high(),
                  bar.low(),
                  bar.close(),
                  bar.volume(),
                  bar.vwap()));
        }
        pageToken = response.nextPageToken();
      } else {
        pageToken = null;
      }
    } while (pageToken != null);

    LOG.info("Fetched {} historical bars for {}", allBars.size(), symbol);
    return allBars;
  }

  /** Parses the JSON once, reads the type field, and dispatches to the appropriate handler. */
  void handleMessage(String raw, WebSocketSession session, List<String> symbols) {
    try {
      var tree = objectMapper.readTree(raw);
      for (var node : tree) {
        var typeCode = node.get("T").asText();
        var messageType = AlpacaMessageType.fromCode(typeCode);
        if (messageType == null) {
          LOG.debug("Ignoring unknown message type: {}", typeCode);
          continue;
        }
        switch (messageType) {
          case SUCCESS -> handleSuccess(node, session, symbols);
          case ERROR -> handleError(node);
          case TRADE -> handleTrade(node);
          case QUOTE -> handleQuote(node);
          case SUBSCRIPTION -> LOG.info("Subscription confirmed: {}", node);
        }
      }
    } catch (JsonProcessingException ex) {
      LOG.error("Failed to parse Alpaca message: {}", raw, ex);
    }
  }

  /** Handles the auth handshake: connected → authenticate → authenticated → subscribe. */
  private void handleSuccess(JsonNode node, WebSocketSession session, List<String> symbols)
      throws JsonProcessingException {
    var control = objectMapper.treeToValue(node, AlpacaControl.class);
    var status = AlpacaAuthStatus.fromMessage(control.message());

    if (status == AlpacaAuthStatus.CONNECTED) {
      LOG.info("Connected to Alpaca, authenticating...");
      var authMsg =
          objectMapper.writeValueAsString(
              Map.of("action", "auth", "key", config.apiKeyId(), "secret", config.apiSecretKey()));
      session.send(Flux.just(session.textMessage(authMsg))).subscribe();

    } else if (status == AlpacaAuthStatus.AUTHENTICATED) {
      LOG.info("Authenticated, subscribing to {}", symbols);
      var subMsg =
          objectMapper.writeValueAsString(
              Map.of("action", "subscribe", "trades", symbols, "quotes", symbols));
      session.send(Flux.just(session.textMessage(subMsg))).subscribe();
    }
  }

  /** Converts an Alpaca trade node into a MarketTick and pushes it to the sink. */
  private void handleTrade(JsonNode node) throws JsonProcessingException {
    var trade = objectMapper.treeToValue(node, AlpacaTrade.class);
    var tick =
        new MarketTick(
            trade.symbol(),
            trade.timestamp(),
            EventType.TRADE,
            trade.price(),
            trade.size(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0L,
            0L,
            0L,
            DataSource.ALPACA);
    tickSink.tryEmitNext(tick);
  }

  /** Converts an Alpaca quote node into a MarketTick and pushes it to the sink. */
  private void handleQuote(JsonNode node) throws JsonProcessingException {
    var quote = objectMapper.treeToValue(node, AlpacaQuote.class);
    var tick =
        new MarketTick(
            quote.symbol(),
            quote.timestamp(),
            EventType.QUOTE,
            BigDecimal.ZERO,
            0L,
            quote.bidPrice(),
            quote.askPrice(),
            quote.bidSize(),
            quote.askSize(),
            0L,
            DataSource.ALPACA);
    tickSink.tryEmitNext(tick);
  }

  /** Logs Alpaca error messages. */
  private void handleError(JsonNode node) throws JsonProcessingException {
    var control = objectMapper.treeToValue(node, AlpacaControl.class);
    LOG.error("Alpaca error — code: {}, message: {}", control.code(), control.message());
  }
}
