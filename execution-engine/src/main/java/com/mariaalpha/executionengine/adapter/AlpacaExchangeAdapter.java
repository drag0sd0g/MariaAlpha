package com.mariaalpha.executionengine.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.config.AlpacaConfig;
import com.mariaalpha.executionengine.handler.AlpacaOrderTypeMapper;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.OrderAck;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("alpaca")
public class AlpacaExchangeAdapter implements ExchangeAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(AlpacaExchangeAdapter.class);

  // Same backoff curve and cap as the market-data Alpaca adapter.
  static final long[] BACKOFF_MS = {1_000L, 2_000L, 4_000L, 8_000L, 16_000L};
  static final int MAX_RECONNECTS = BACKOFF_MS.length;

  private final AlpacaConfig config;
  private final ObjectMapper objectMapper;
  private final AlpacaOrderTypeMapper typeMapper;
  private final HttpClient httpClient;
  private final OkHttpClient wsClient; // lightweight websocket client
  private final AtomicBoolean connected = new AtomicBoolean(false);
  private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
  private final ScheduledExecutorService reconnectScheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "alpaca-trade-updates-reconnect");
            t.setDaemon(true);
            return t;
          });
  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
  private volatile Consumer<ExecutionReport> reportCallback;
  private volatile WebSocket webSocket;

  public AlpacaExchangeAdapter(
      AlpacaConfig config, ObjectMapper objectMapper, AlpacaOrderTypeMapper typeMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.typeMapper = typeMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.wsClient = new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(30)).build();
  }

  @Override
  public OrderAck submitOrder(ExecutionInstruction instruction) {
    var order = instruction.order();
    Map<String, String> body = new HashMap<>();
    body.put("symbol", order.getSymbol());
    body.put("qty", String.valueOf(order.getQuantity()));
    body.put("side", order.getSide().name().toLowerCase());
    body.put("type", typeMapper.wireType(order.getOrderType()));
    body.put("time_in_force", typeMapper.wireTif(instruction));
    body.put("client_order_id", order.getOrderId());
    if (instruction.adjustedLimitPrice() != null) {
      body.put("limit_price", instruction.adjustedLimitPrice().toPlainString());
    } else if (order.getLimitPrice() != null) {
      body.put("limit_price", order.getLimitPrice().toPlainString());
    }
    if (order.getStopPrice() != null) {
      body.put("stop_price", order.getStopPrice().toPlainString());
    }

    try {
      var json = objectMapper.writeValueAsString(body);
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.baseUrl() + "/v2/orders"))
              .header("APCA-API-KEY-ID", config.apiKey())
              .header("APCA-API-SECRET-KEY", config.apiSecret())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .timeout(Duration.ofSeconds(5))
              .build();

      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200 || response.statusCode() == 201) {
        var node = objectMapper.readTree(response.body());
        var exchangeId = node.get("id").asText();
        return new OrderAck(order.getOrderId(), exchangeId, true, "");
      } else {
        var errMsg = response.body();
        LOG.warn("Alpaca order rejected: {}", errMsg);
        return new OrderAck(order.getOrderId(), "", false, errMsg);
      }

    } catch (Exception ex) {
      LOG.error("Alpaca order submission failed: {}", ex.getMessage(), ex);
      return new OrderAck(order.getOrderId(), "", false, ex.getMessage());
    }
  }

  @Override
  public OrderAck cancelOrder(String exchangeOrderId) {
    try {
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.baseUrl() + "/v2/orders/" + exchangeOrderId))
              .header("APCA-API-KEY-ID", config.apiKey())
              .header("APCA-API-SECRET-KEY", config.apiSecret())
              .DELETE()
              .timeout(Duration.ofSeconds(5))
              .build();

      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      boolean success = response.statusCode() == 200 || response.statusCode() == 204;
      return new OrderAck("", exchangeOrderId, success, success ? "cancelled" : response.body());
    } catch (Exception ex) {
      LOG.error("Alpaca order cancellation failed: {}", ex.getMessage(), ex);
      return new OrderAck("", exchangeOrderId, false, ex.getMessage());
    }
  }

  @Override
  public void onExecutionReport(Consumer<ExecutionReport> callback) {
    this.reportCallback = callback;
  }

  @PostConstruct
  @Override
  public void start() {
    reconnectAttempt.set(0);
    doConnect();
  }

  void doConnect() {
    var request = new Request.Builder().url(config.websocketUrl()).build();
    this.webSocket =
        wsClient.newWebSocket(
            request,
            new AlpacaWebSocketListener(
                config,
                objectMapper,
                () -> reportCallback,
                connected,
                this::scheduleReconnect,
                () -> reconnectAttempt.set(0)));
  }

  void scheduleReconnect() {
    if (shuttingDown.get()) {
      return;
    }
    // Retry forever with the backoff capped at the last rung — giving up permanently would
    // silently drop every subsequent fill until the process is restarted.
    var attempt = reconnectAttempt.getAndIncrement();
    var delayMs = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
    LOG.warn("Alpaca trade_updates: scheduling reconnect attempt {} in {}ms", attempt + 1, delayMs);
    reconnectScheduler.schedule(this::doConnect, delayMs, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  @Override
  public void shutdown() {
    shuttingDown.set(true);
    if (webSocket != null) {
      webSocket.close(1000, "shutdown");
    }
    reconnectScheduler.shutdownNow();
    wsClient.dispatcher().executorService().shutdown();
  }

  @Override
  public boolean isHealthy() {
    return connected.get();
  }
}
