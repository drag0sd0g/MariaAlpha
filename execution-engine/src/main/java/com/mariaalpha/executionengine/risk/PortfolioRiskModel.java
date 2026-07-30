package com.mariaalpha.executionengine.risk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory cache of the latest covariance model published by analytics-service (roadmap 4.6.1).
 *
 * <p><strong>Why the daily covariance is precomputed.</strong> The pre-trade risk chain is on the
 * order hot path. Building {@code Sigma_d = D_d C D_d} costs O(N^2) but only changes when a new
 * model arrives (every few minutes), so it is materialised once in {@link #update} and read
 * lock-free thereafter through a {@code volatile} holder. Per order the work is then a single
 * quadratic form, and the <em>projected</em> VaR — which differs from the current one in exactly
 * one coordinate — uses the rank-1 identity
 *
 * <pre>
 *   sigma_new^2 = sigma_old^2 + 2 delta (Sigma_d v)_k + delta^2 (Sigma_d)_kk
 * </pre>
 *
 * which is O(N) given a cached {@code Sigma_d v}. {@link #sigmaAfterDelta} implements it and {@code
 * PortfolioRiskModelTest} asserts it against a full recompute.
 */
@Component
public class PortfolioRiskModel {

  private static final Logger LOG = LoggerFactory.getLogger(PortfolioRiskModel.class);

  /** Immutable snapshot of everything the check needs, swapped atomically. */
  private record Holder(
      String modelId,
      Instant generatedAt,
      String source,
      int observations,
      List<String> symbols,
      Map<String, Integer> index,
      double[] dailyVolatility,
      double[][] dailyCovariance) {}

  private volatile Holder holder;

  /** Replaces the cached model. The snapshot's arrays are deep-copied; callers may reuse theirs. */
  public void update(RiskModelSnapshot snapshot) {
    snapshot.validate();
    List<String> symbols = List.copyOf(snapshot.symbols());
    int n = symbols.size();

    Map<String, Integer> index = new HashMap<>(n * 2);
    for (int i = 0; i < n; i++) {
      index.put(symbols.get(i), i);
    }

    double sqrtDays = Math.sqrt(snapshot.tradingDaysPerYear());
    double[] dailyVol = new double[n];
    for (int i = 0; i < n; i++) {
      dailyVol[i] = snapshot.annualizedVolatility()[i] / sqrtDays;
    }

    double[][] covariance = new double[n][n];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        covariance[i][j] = dailyVol[i] * dailyVol[j] * snapshot.correlation()[i][j];
      }
    }

    this.holder =
        new Holder(
            snapshot.modelId(),
            snapshot.generatedAt(),
            snapshot.source(),
            snapshot.observations(),
            symbols,
            Map.copyOf(index),
            dailyVol,
            covariance);
    LOG.info(
        "Risk model {} applied: {} symbols, source={}, observations={}, generatedAt={}",
        snapshot.modelId(),
        n,
        snapshot.source(),
        snapshot.observations(),
        snapshot.generatedAt());
  }

  public boolean isPresent() {
    return holder != null;
  }

  /** True when a model exists and is younger than {@code maxAge}. */
  public boolean isUsable(Duration maxAge, Clock clock) {
    Holder current = holder;
    if (current == null) {
      return false;
    }
    if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
      return true;
    }
    return !age(clock).minus(maxAge).isPositive();
  }

  /** Age of the cached model, or {@link Duration#ZERO} when there is none. */
  public Duration age(Clock clock) {
    Holder current = holder;
    if (current == null) {
      return Duration.ZERO;
    }
    Duration elapsed = Duration.between(current.generatedAt(), clock.instant());
    return elapsed.isNegative() ? Duration.ZERO : elapsed;
  }

  public OptionalInt indexOf(String symbol) {
    Holder current = holder;
    if (current == null) {
      return OptionalInt.empty();
    }
    Integer found = current.index().get(symbol);
    return found == null ? OptionalInt.empty() : OptionalInt.of(found);
  }

  public int size() {
    Holder current = holder;
    return current == null ? 0 : current.symbols().size();
  }

  public String modelId() {
    Holder current = holder;
    return current == null ? null : current.modelId();
  }

  public List<String> symbols() {
    Holder current = holder;
    return current == null ? List.of() : current.symbols();
  }

  /** Daily volatility of a modelled symbol, or {@code 0} when it is not in the model. */
  public double dailyVolatility(String symbol) {
    Holder current = holder;
    if (current == null) {
      return 0.0;
    }
    Integer i = current.index().get(symbol);
    return i == null ? 0.0 : current.dailyVolatility()[i];
  }

  /** {@code Sigma_d v} for a notional vector in model order. */
  public double[] covarianceTimesVector(double[] notionals) {
    Holder current = holder;
    if (current == null) {
      return new double[0];
    }
    int n = current.symbols().size();
    requireLength(notionals, n);
    double[] out = new double[n];
    for (int i = 0; i < n; i++) {
      double sum = 0.0;
      double[] row = current.dailyCovariance()[i];
      for (int j = 0; j < n; j++) {
        sum += row[j] * notionals[j];
      }
      out[i] = sum;
    }
    return out;
  }

  /** {@code sqrt(v' Sigma_d v)} in USD, for a notional vector in model order. */
  public double portfolioSigma(double[] notionals) {
    Holder current = holder;
    if (current == null) {
      return 0.0;
    }
    int n = current.symbols().size();
    requireLength(notionals, n);
    double[] product = covarianceTimesVector(notionals);
    double variance = 0.0;
    for (int i = 0; i < n; i++) {
      variance += notionals[i] * product[i];
    }
    return variance <= 0 ? 0.0 : Math.sqrt(variance);
  }

  /**
   * Volatility after changing coordinate {@code k} by {@code delta}, via the rank-1 update.
   *
   * @param covarianceTimesV cached {@code Sigma_d v} for the unchanged vector
   * @param sigma the unchanged vector's volatility
   */
  public double sigmaAfterDelta(double[] covarianceTimesV, double sigma, int k, double delta) {
    Holder current = holder;
    if (current == null) {
      return sigma;
    }
    int n = current.symbols().size();
    if (k < 0 || k >= n) {
      throw new IndexOutOfBoundsException("coordinate " + k + " is outside the model's " + n);
    }
    requireLength(covarianceTimesV, n);
    double variance =
        sigma * sigma
            + 2.0 * delta * covarianceTimesV[k]
            + delta * delta * current.dailyCovariance()[k][k];
    return variance <= 0 ? 0.0 : Math.sqrt(variance);
  }

  private static void requireLength(double[] values, int expected) {
    if (values == null || values.length != expected) {
      throw new IllegalArgumentException(
          "vector length "
              + (values == null ? "null" : values.length)
              + " != model size "
              + expected);
    }
  }
}
