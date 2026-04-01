package com.mariaalpha.marketdatagateway.adapter.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.marketdatagateway.config.AlpacaMarketDataConfig;
import com.mariaalpha.marketdatagateway.model.BarTimeframe;
import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.EventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AlpacaMarketDataAdapterTest {

  private AlpacaMarketDataAdapter adapter;
  private WebSocketSession mockSession;

  @BeforeEach
  void setUp() {
    var config = new AlpacaMarketDataConfig("wss://unused", "test-key", "test-secret");
    adapter = new AlpacaMarketDataAdapter(config);
    mockSession = mock(WebSocketSession.class);
    when(mockSession.textMessage(any(String.class))).thenReturn(mock(WebSocketMessage.class));
    when(mockSession.send(any())).thenReturn(Mono.empty());
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
  void historicalBarsThrowsUnsupported() {
    assertThatThrownBy(
            () ->
                adapter.getHistoricalBars(
                    "AAPL", LocalDate.now(), LocalDate.now(), BarTimeframe.ONE_DAY))
        .isInstanceOf(UnsupportedOperationException.class);
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
