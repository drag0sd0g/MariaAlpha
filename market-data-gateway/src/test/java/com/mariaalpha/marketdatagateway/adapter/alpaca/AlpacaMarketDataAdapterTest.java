package com.mariaalpha.marketdatagateway.adapter.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.marketdatagateway.config.AlpacaMarketDataConfig;
import com.mariaalpha.marketdatagateway.model.BarTimeframe;
import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.EventType;
import com.mariaalpha.marketdatagateway.model.HistoricalBar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AlpacaMarketDataAdapterTest {

  private AlpacaMarketDataAdapter adapter;
  private WebSocketSession mockSession;
  private MockWebServer mockServer;

  @BeforeEach
  void setUp() throws Exception {
    mockServer = new MockWebServer();
    mockServer.start();

    var config =
        new AlpacaMarketDataConfig(
            "wss://unused", mockServer.url("/").toString(), "test-key", "test-secret");
    adapter = new AlpacaMarketDataAdapter(config);
    mockSession = mock(WebSocketSession.class);
    when(mockSession.textMessage(any(String.class))).thenReturn(mock(WebSocketMessage.class));
    when(mockSession.send(any())).thenReturn(Mono.empty());
  }

  @AfterEach
  void tearDown() throws Exception {
    mockServer.shutdown();
  }

  @Test
  void tradeMessageProducesMarketTick() {
    var json =
        "[{\"T\":\"t\",\"S\":\"AAPL\",\"p\":178.52,\"s\":100,"
            + "\"t\":\"2026-03-24T14:30:00.123Z\",\"c\":[\"@\"],\"z\":\"C\"}]";

    adapter.handleMessage(json, mockSession, List.of("AAPL"));

    StepVerifier.create(adapter.streamTicks().next())
        .assertNext(
            tick -> {
              assertThat(tick.symbol()).isEqualTo("AAPL");
              assertThat(tick.eventType()).isEqualTo(EventType.TRADE);
              assertThat(tick.price()).isEqualByComparingTo(new BigDecimal("178.52"));
              assertThat(tick.size()).isEqualTo(100L);
              assertThat(tick.timestamp()).isEqualTo(Instant.parse("2026-03-24T14:30:00.123Z"));
              assertThat(tick.source()).isEqualTo(DataSource.ALPACA);
              assertThat(tick.bidPrice()).isEqualByComparingTo(BigDecimal.ZERO);
              assertThat(tick.askPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            })
        .verifyComplete();
  }

  @Test
  void multipleTradesInSingleMessage() {
    var json =
        "[{\"T\":\"t\",\"S\":\"AAPL\",\"p\":178.52,\"s\":100,"
            + "\"t\":\"2026-03-24T14:30:00.123Z\",\"c\":[],\"z\":\"C\"},"
            + "{\"T\":\"t\",\"S\":\"MSFT\",\"p\":415.20,\"s\":50,"
            + "\"t\":\"2026-03-24T14:30:00.456Z\",\"c\":[],\"z\":\"C\"}]";

    adapter.handleMessage(json, mockSession, List.of("AAPL", "MSFT"));

    StepVerifier.create(adapter.streamTicks().take(2))
        .assertNext(tick -> assertThat(tick.symbol()).isEqualTo("AAPL"))
        .assertNext(tick -> assertThat(tick.symbol()).isEqualTo("MSFT"))
        .verifyComplete();
  }

  @Test
  void quoteMessageProducesMarketTick() {
    var json =
        "[{\"T\":\"q\",\"S\":\"AAPL\",\"bp\":178.50,\"ap\":178.54,"
            + "\"bs\":200,\"as\":150,\"t\":\"2026-03-24T14:30:00.123Z\","
            + "\"c\":[\"R\"],\"z\":\"C\"}]";

    adapter.handleMessage(json, mockSession, List.of("AAPL"));

    StepVerifier.create(adapter.streamTicks().next())
        .assertNext(
            tick -> {
              assertThat(tick.symbol()).isEqualTo("AAPL");
              assertThat(tick.eventType()).isEqualTo(EventType.QUOTE);
              assertThat(tick.bidPrice()).isEqualByComparingTo(new BigDecimal("178.50"));
              assertThat(tick.askPrice()).isEqualByComparingTo(new BigDecimal("178.54"));
              assertThat(tick.bidSize()).isEqualTo(200L);
              assertThat(tick.askSize()).isEqualTo(150L);
              assertThat(tick.source()).isEqualTo(DataSource.ALPACA);
              assertThat(tick.price()).isEqualByComparingTo(BigDecimal.ZERO);
            })
        .verifyComplete();
  }

  @Test
  void connectedMessageTriggersAuth() {
    var json = "[{\"T\":\"success\",\"msg\":\"connected\"}]";

    adapter.handleMessage(json, mockSession, List.of("AAPL"));

    verify(mockSession).textMessage(any(String.class));
    verify(mockSession).send(any());
  }

  @Test
  void authenticatedMessageTriggersSubscription() {
    var json = "[{\"T\":\"success\",\"msg\":\"authenticated\"}]";

    adapter.handleMessage(json, mockSession, List.of("AAPL"));

    verify(mockSession).textMessage(any(String.class));
    verify(mockSession).send(any());
  }

  @Test
  void errorMessageDoesNotProduceTick() {
    var json = "[{\"T\":\"error\",\"code\":402,\"msg\":\"auth failed\"}]";

    adapter.handleMessage(json, mockSession, List.of("AAPL"));
    adapter.disconnect();

    StepVerifier.create(adapter.streamTicks()).verifyComplete();
  }

  @Test
  void malformedJsonDoesNotThrow() {
    adapter.handleMessage("not valid json", mockSession, List.of("AAPL"));
    adapter.disconnect();

    StepVerifier.create(adapter.streamTicks()).verifyComplete();
  }

  @Test
  void unknownMessageTypeIsIgnored() {
    var json = "[{\"T\":\"unknown_type\",\"S\":\"AAPL\"}]";

    adapter.handleMessage(json, mockSession, List.of("AAPL"));
    adapter.disconnect();

    StepVerifier.create(adapter.streamTicks()).verifyComplete();
  }

  @Test
  void getHistoricalBarsReturnsBarsFromApi() {
    var responseJson =
        """
            {
              "bars": [
                {"t":"2026-01-02T05:00:00Z","o":185.59,
             "h":186.10,"l":184.27,"c":185.64,
             "v":45234567,"n":123456,"vw":185.42},
            {"t":"2026-01-03T05:00:00Z","o":186.00,
             "h":187.50,"l":185.80,"c":187.20,
             "v":38000000,"n":100000,"vw":186.80}
              ],
              "symbol": "AAPL",
              "next_page_token": null
            }
            """;
    mockServer.enqueue(
        new MockResponse().setBody(responseJson).setHeader("Content-Type", "application/json"));

    List<HistoricalBar> bars =
        adapter.getHistoricalBars(
            "AAPL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 4), BarTimeframe.ONE_DAY);

    assertThat(bars).hasSize(2);
    assertThat(bars.get(0).symbol()).isEqualTo("AAPL");
    assertThat(bars.get(0).barDate()).isEqualTo(LocalDate.of(2026, 1, 2));
    assertThat(bars.get(0).open()).isEqualByComparingTo(new BigDecimal("185.59"));
    assertThat(bars.get(0).high()).isEqualByComparingTo(new BigDecimal("186.10"));
    assertThat(bars.get(0).low()).isEqualByComparingTo(new BigDecimal("184.27"));
    assertThat(bars.get(0).close()).isEqualByComparingTo(new BigDecimal("185.64"));
    assertThat(bars.get(0).volume()).isEqualTo(45234567L);
    assertThat(bars.get(0).vwap()).isEqualByComparingTo(new BigDecimal("185.42"));
    assertThat(bars.get(1).barDate()).isEqualTo(LocalDate.of(2026, 1, 3));
  }

  @Test
  void getHistoricalBarsHandlesPagination() {
    var page1 =
        """
            {
              "bars": [{"t":"2026-01-02T05:00:00Z",
                "o":185.59,"h":186.10,"l":184.27,
                "c":185.64,"v":45234567,
                "n":123456,"vw":185.42}],
              "symbol": "AAPL",
              "next_page_token": "token123"
            }
            """;
    var page2 =
        """
            {
              "bars": [{"t":"2026-01-03T05:00:00Z",
                "o":186.00,"h":187.50,"l":185.80,
                "c":187.20,"v":38000000,
                "n":100000,"vw":186.80}],
              "symbol": "AAPL",
              "next_page_token": null
            }
            """;
    mockServer.enqueue(
        new MockResponse().setBody(page1).setHeader("Content-Type", "application/json"));
    mockServer.enqueue(
        new MockResponse().setBody(page2).setHeader("Content-Type", "application/json"));

    List<HistoricalBar> bars =
        adapter.getHistoricalBars(
            "AAPL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 4), BarTimeframe.ONE_DAY);

    assertThat(bars).hasSize(2);
    assertThat(bars.get(0).barDate()).isEqualTo(LocalDate.of(2026, 1, 2));
    assertThat(bars.get(1).barDate()).isEqualTo(LocalDate.of(2026, 1, 3));
  }

  @Test
  void getHistoricalBarsReturnsEmptyListWhenNoBars() {
    var responseJson =
        """
        {"bars": [], "symbol": "AAPL", "next_page_token": null}
        """;
    mockServer.enqueue(
        new MockResponse().setBody(responseJson).setHeader("Content-Type", "application/json"));

    List<HistoricalBar> bars =
        adapter.getHistoricalBars(
            "AAPL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 4), BarTimeframe.ONE_DAY);

    assertThat(bars).isEmpty();
  }

  @Test
  void disconnectCompletesTickStream() {
    var json =
        "[{\"T\":\"t\",\"S\":\"AAPL\",\"p\":178.52,\"s\":100,"
            + "\"t\":\"2026-03-24T14:30:00.123Z\",\"c\":[],\"z\":\"C\"}]";

    adapter.handleMessage(json, mockSession, List.of("AAPL"));
    adapter.disconnect();

    StepVerifier.create(adapter.streamTicks()).expectNextCount(1).verifyComplete();
  }
}
