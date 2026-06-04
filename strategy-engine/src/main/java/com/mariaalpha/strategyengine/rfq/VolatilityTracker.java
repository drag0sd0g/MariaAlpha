package com.mariaalpha.strategyengine.rfq;

import org.springframework.stereotype.Component;

/**
 * Realised volatility estimator used by the RFQ pricing engine to widen the spread.
 *
 * <p>Reads the rolling mid-price history from {@link MarketStateCache}, converts it into log
 * returns, and computes the sample standard deviation expressed in basis points (1 bp = 0.01%). The
 * resulting number is the "per-tick" volatility — the units in which {@code volScalar} multiplies
 * it are bps per tick, scaled linearly to bps of spread half-width. Annualisation is intentionally
 * deferred: the spread parameter is calibrated against the same window definition.
 *
 * <p>Returns 0 when fewer than two mid-price samples are available (insufficient data → don't
 * widen).
 */
@Component
public class VolatilityTracker {

  private final MarketStateCache cache;

  public VolatilityTracker(MarketStateCache cache) {
    this.cache = cache;
  }

  /** Sample stdev of log returns, expressed in basis points (1 bp = 1e-4). */
  public double realizedVolBps(String symbol) {
    var history = cache.midHistory(symbol).orElse(new double[0]);
    if (history.length < 2) {
      return 0.0;
    }
    int n = history.length - 1;
    double[] returns = new double[n];
    for (int i = 0; i < n; i++) {
      double prev = history[i];
      double next = history[i + 1];
      if (prev <= 0.0 || next <= 0.0) {
        returns[i] = 0.0;
      } else {
        returns[i] = Math.log(next / prev);
      }
    }
    double mean = 0.0;
    for (double r : returns) {
      mean += r;
    }
    mean /= n;

    double sse = 0.0;
    for (double r : returns) {
      double d = r - mean;
      sse += d * d;
    }
    double variance = n > 1 ? sse / (n - 1) : sse;
    double stdev = Math.sqrt(variance);
    return stdev * 10_000.0;
  }
}
