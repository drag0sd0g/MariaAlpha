package com.mariaalpha.strategyengine.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure functions over an equity curve. Sharpe is annualised from per-minute returns, matching the
 * per-minute sampling of the curve the engine builds.
 */
public final class BacktestMetricsCalculator {

  /** US equities: ~252 trading days × 390 minutes per regular session. */
  static final double MINUTES_PER_TRADING_YEAR = 252.0 * 390.0;

  private BacktestMetricsCalculator() {}

  public static double totalReturnPct(BigDecimal initial, BigDecimal finalEquity) {
    if (initial == null || initial.signum() == 0) {
      return 0.0;
    }
    return finalEquity
        .subtract(initial)
        .divide(initial, 10, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .doubleValue();
  }

  public static double maxDrawdownPct(List<EquityPoint> curve) {
    double peak = Double.NEGATIVE_INFINITY;
    double maxDrawdown = 0.0;
    for (var point : curve) {
      double equity = point.equity().doubleValue();
      if (equity > peak) {
        peak = equity;
      }
      if (peak > 0) {
        double drawdown = (peak - equity) / peak;
        if (drawdown > maxDrawdown) {
          maxDrawdown = drawdown;
        }
      }
    }
    return maxDrawdown * 100.0;
  }

  public static double annualizedSharpe(List<EquityPoint> curve) {
    if (curve.size() < 3) {
      return 0.0;
    }
    int n = curve.size() - 1;
    var returns = new double[n];
    for (int i = 1; i < curve.size(); i++) {
      double prev = curve.get(i - 1).equity().doubleValue();
      double current = curve.get(i).equity().doubleValue();
      returns[i - 1] = prev == 0.0 ? 0.0 : (current - prev) / prev;
    }
    double mean = 0.0;
    for (double r : returns) {
      mean += r;
    }
    mean /= n;
    double variance = 0.0;
    for (double r : returns) {
      variance += (r - mean) * (r - mean);
    }
    variance /= n;
    double stdDev = Math.sqrt(variance);
    if (stdDev == 0.0) {
      return 0.0;
    }
    return (mean / stdDev) * Math.sqrt(MINUTES_PER_TRADING_YEAR);
  }
}
