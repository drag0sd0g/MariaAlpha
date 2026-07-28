package com.mariaalpha.strategyengine.backtest.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SimulationClockTest {

  private static final Instant START = Instant.parse("2026-03-24T14:30:00Z");

  @Test
  void reportsStartInstantUntilAdvanced() {
    var clock = new SimulationClock(START);
    assertThat(clock.instant()).isEqualTo(START);
    assertThat(clock.millis()).isEqualTo(START.toEpochMilli());
    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void advanceToMovesSimulatedNow() {
    var clock = new SimulationClock(START);
    var later = START.plusSeconds(90);
    clock.advanceTo(later);
    assertThat(clock.instant()).isEqualTo(later);
  }

  @Test
  void zonedViewSharesUnderlyingInstant() {
    var clock = new SimulationClock(START);
    var eastern = clock.withZone(ZoneId.of("America/New_York"));
    var later = START.plusSeconds(60);

    clock.advanceTo(later);

    assertThat(eastern.instant()).isEqualTo(later);
    assertThat(eastern.getZone()).isEqualTo(ZoneId.of("America/New_York"));
  }
}
