package com.mariaalpha.marketdatagateway.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.EventType;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import reactor.core.publisher.Sinks;

class TickReadinessIndicatorTest {

  private final Sinks.Many<MarketTick> tickSink = Sinks.many().multicast().onBackpressureBuffer();
  private final MarketDataAdapter adapter = mock(MarketDataAdapter.class);
  private TickReadinessIndicator indicator;

  private void initIndicator() {
    when(adapter.streamTicks()).thenReturn(tickSink.asFlux());
    indicator = new TickReadinessIndicator(adapter);
    indicator.start();
  }

  @AfterEach
  void tearDown() {
    if (indicator != null) {
      indicator.stop();
    }
  }

  @Test
  void reportsOutOfServiceBeforeFirstTick() {
    initIndicator();

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(health.getDetails()).containsEntry("firstTickReceived", false);
  }

  @Test
  void reportsUpAfterFirstTick() {
    initIndicator();

    tickSink.tryEmitNext(sampleTick());

    assertThat(indicator.isReady()).isTrue();
    var health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("firstTickReceived", true);
  }

  @Test
  void remainsReadyAfterMultipleTicks() {
    initIndicator();

    tickSink.tryEmitNext(sampleTick());
    tickSink.tryEmitNext(sampleTick());

    assertThat(indicator.isReady()).isTrue();
  }

  private static MarketTick sampleTick() {
    return new MarketTick(
        "AAPL",
        Instant.parse("2026-03-24T14:30:00.123Z"),
        EventType.TRADE,
        new BigDecimal("178.52"),
        100L,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0L,
        0L,
        0L,
        DataSource.ALPACA,
        false);
  }
}
