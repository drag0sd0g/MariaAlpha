package com.mariaalpha.strategyengine.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BacktestMetricsCalculatorTest {

  private static List<EquityPoint> curve(double... equities) {
    var points = new ArrayList<EquityPoint>();
    var base = Instant.parse("2026-03-24T14:30:00Z");
    for (int i = 0; i < equities.length; i++) {
      points.add(new EquityPoint(base.plusSeconds(i * 60L), BigDecimal.valueOf(equities[i])));
    }
    return points;
  }

  @Test
  void totalReturnPctIsRelativeToInitial() {
    assertThat(
            BacktestMetricsCalculator.totalReturnPct(
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(110_000)))
        .isEqualTo(10.0);
    assertThat(
            BacktestMetricsCalculator.totalReturnPct(
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(95_000)))
        .isEqualTo(-5.0);
  }

  @Test
  void maxDrawdownFindsLargestPeakToTroughDecline() {
    // Peak 120, trough 90 -> (120-90)/120 = 25%.
    double mdd = BacktestMetricsCalculator.maxDrawdownPct(curve(100, 120, 110, 90, 130));
    assertThat(mdd).isEqualTo(25.0);
  }

  @Test
  void maxDrawdownIsZeroForMonotonicIncrease() {
    assertThat(BacktestMetricsCalculator.maxDrawdownPct(curve(100, 101, 102, 103))).isZero();
  }

  @Test
  void sharpeIsPositiveForSteadyGainsAndZeroForFlatEquity() {
    double sharpe = BacktestMetricsCalculator.annualizedSharpe(curve(100, 101, 102, 103, 104));
    assertThat(sharpe).isGreaterThan(0.0);
    assertThat(BacktestMetricsCalculator.annualizedSharpe(curve(100, 100, 100, 100))).isZero();
  }

  @Test
  void sharpeNeedsAtLeastThreePoints() {
    assertThat(BacktestMetricsCalculator.annualizedSharpe(curve(100, 105))).isZero();
  }
}
