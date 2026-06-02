package com.mariaalpha.executionengine.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionSnapshotTest {

  @Test
  void notionalUsesMarkPriceWhenPresent() {
    var snap =
        new PositionSnapshot(
            "AAPL",
            BigDecimal.valueOf(10),
            BigDecimal.valueOf(150),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.valueOf(200),
            Instant.now());
    assertThat(snap.notional()).isEqualByComparingTo("2000");
  }

  @Test
  void notionalFallsBackToAvgEntryWhenMarkMissing() {
    var snap =
        new PositionSnapshot(
            "MSFT",
            BigDecimal.valueOf(5),
            BigDecimal.valueOf(100),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            Instant.now());
    assertThat(snap.notional()).isEqualByComparingTo("500");
  }

  @Test
  void notionalIsZeroWhenAllPricesAbsent() {
    var snap =
        new PositionSnapshot(
            "TSLA",
            BigDecimal.valueOf(5),
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            Instant.now());
    assertThat(snap.notional()).isEqualByComparingTo("0");
  }

  @Test
  void notionalIsZeroWhenQuantityNull() {
    var snap =
        new PositionSnapshot(
            "NVDA", null, BigDecimal.TEN, null, null, BigDecimal.TEN, Instant.now());
    assertThat(snap.notional()).isEqualByComparingTo("0");
  }

  @Test
  void shortPositionsHaveNegativeNotional() {
    var snap =
        new PositionSnapshot(
            "GOOG",
            BigDecimal.valueOf(-3),
            BigDecimal.valueOf(50),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.valueOf(60),
            Instant.now());
    assertThat(snap.notional()).isEqualByComparingTo("-180");
  }
}
