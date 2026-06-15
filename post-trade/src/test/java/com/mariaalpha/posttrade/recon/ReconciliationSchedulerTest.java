package com.mariaalpha.posttrade.recon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReconciliationSchedulerTest {

  @Test
  void previousTradingDaySkipsWeekends() {
    assertThat(ReconciliationScheduler.previousTradingDay(LocalDate.of(2026, 6, 1)))
        .isEqualTo(LocalDate.of(2026, 5, 29));
  }

  @Test
  void previousTradingDayForMidWeekIsPreviousDay() {
    assertThat(ReconciliationScheduler.previousTradingDay(LocalDate.of(2026, 6, 3)))
        .isEqualTo(LocalDate.of(2026, 6, 2));
  }

  @Test
  void previousTradingDayForSundaySkipsToFriday() {
    assertThat(ReconciliationScheduler.previousTradingDay(LocalDate.of(2026, 5, 31)))
        .isEqualTo(LocalDate.of(2026, 5, 29));
  }
}
