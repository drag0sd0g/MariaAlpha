package com.mariaalpha.marketdatagateway.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import com.mariaalpha.marketdatagateway.config.KafkaPublisherConfig;
import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.EventType;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.publisher.Sinks;

class TickKafkaPublisherTest {

  private static final String TOPIC = "market-data.ticks";

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final Sinks.Many<MarketTick> tickSink = Sinks.many().multicast().onBackpressureBuffer();
  private TickKafkaPublisher publisher;

  @BeforeEach
  void setUp() {
    var objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    var config = new KafkaPublisherConfig(TOPIC);
    var adapter = mock(MarketDataAdapter.class);
    when(adapter.streamTicks()).thenReturn(tickSink.asFlux());
    when(kafkaTemplate.send(any(String.class), any(String.class), any(String.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    publisher = new TickKafkaPublisher(adapter, kafkaTemplate, objectMapper, config, meterRegistry);
    publisher.start();
  }

  @AfterEach
  void tearDown() {
    publisher.stop();
  }

  @Test
  void publishesTickToCorrectTopicWithSymbolAsKey() {
    var tick = tradeTick("AAPL", "178.52");

    tickSink.tryEmitNext(tick);

    verify(kafkaTemplate, timeout(1000)).send(eq(TOPIC), eq("AAPL"), any(String.class));
  }

  @Test
  void publishedJsonContainsExpectedFields() {
    var tick = tradeTick("MSFT", "415.20");

    tickSink.tryEmitNext(tick);

    verify(kafkaTemplate, timeout(1000))
        .send(
            eq(TOPIC),
            eq("MSFT"),
            argThat(
                json ->
                    json.contains("\"symbol\":\"MSFT\"")
                        && json.contains("\"eventType\":\"TRADE\"")
                        && json.contains("\"source\":\"ALPACA\"")));
  }

  @Test
  void incrementsTickCounterMetric() {
    tickSink.tryEmitNext(tradeTick("AAPL", "178.52"));
    tickSink.tryEmitNext(tradeTick("AAPL", "178.55"));
    tickSink.tryEmitNext(quoteTick("MSFT"));

    verify(kafkaTemplate, timeout(1000).times(3))
        .send(eq(TOPIC), any(String.class), any(String.class));

    var aaplTradeCounter =
        meterRegistry
            .find("mariaalpha_md_ticks_received_total")
            .tag("symbol", "AAPL")
            .tag("event_type", "TRADE")
            .counter();
    assertThat(aaplTradeCounter).isNotNull();
    assertThat(aaplTradeCounter.count()).isEqualTo(2.0);

    var msftQuoteCounter =
        meterRegistry
            .find("mariaalpha_md_ticks_received_total")
            .tag("symbol", "MSFT")
            .tag("event_type", "QUOTE")
            .counter();
    assertThat(msftQuoteCounter).isNotNull();
    assertThat(msftQuoteCounter.count()).isEqualTo(1.0);
  }

  @Test
  void handlesMultipleTicksFromDifferentSymbols() {
    tickSink.tryEmitNext(tradeTick("AAPL", "178.52"));
    tickSink.tryEmitNext(tradeTick("MSFT", "415.20"));
    tickSink.tryEmitNext(tradeTick("GOOGL", "140.00"));

    verify(kafkaTemplate, timeout(1000)).send(eq(TOPIC), eq("AAPL"), any(String.class));
    verify(kafkaTemplate, timeout(1000)).send(eq(TOPIC), eq("MSFT"), any(String.class));
    verify(kafkaTemplate, timeout(1000)).send(eq(TOPIC), eq("GOOGL"), any(String.class));
  }

  @Test
  void recordsTickLatencyHistogram() {
    tickSink.tryEmitNext(tradeTick("AAPL", "178.52"));

    verify(kafkaTemplate, timeout(1000)).send(eq(TOPIC), eq("AAPL"), any(String.class));

    var timer = meterRegistry.find("mariaalpha_md_tick_latency_ms").tag("symbol", "AAPL").timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isPositive();
  }

  private static MarketTick tradeTick(String symbol, String price) {
    return new MarketTick(
        symbol,
        Instant.parse("2026-03-24T14:30:00.123Z"),
        EventType.TRADE,
        new BigDecimal(price),
        100L,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0L,
        0L,
        0L,
        DataSource.ALPACA);
  }

  private static MarketTick quoteTick(String symbol) {
    return new MarketTick(
        symbol,
        Instant.parse("2026-03-24T14:30:00.123Z"),
        EventType.QUOTE,
        BigDecimal.ZERO,
        0L,
        new BigDecimal("415.18"),
        new BigDecimal("415.24"),
        100L,
        80L,
        0L,
        DataSource.ALPACA);
  }
}
