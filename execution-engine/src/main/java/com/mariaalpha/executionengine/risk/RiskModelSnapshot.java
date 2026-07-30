package com.mariaalpha.executionengine.risk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * Wire contract for the {@code analytics.risk-model} topic (roadmap 4.6.1).
 *
 * <p>Deliberately a plain Jackson DTO holding the raw arrays: it is package-private in intent and
 * never handed out beyond {@link PortfolioRiskModel#update}, which copies everything it needs. That
 * keeps the mutable-array exposure contained to one class rather than spread across every caller.
 *
 * <p>The model carries <em>volatilities plus a correlation matrix</em> rather than a raw covariance
 * so the two halves can be trusted independently — a symbol whose estimated volatility looks wrong
 * can fall back to configured reference data while keeping the estimated correlation structure.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskModelSnapshot(
    String modelId,
    Instant generatedAt,
    String estimator,
    String source,
    int observations,
    double tradingDaysPerYear,
    List<String> symbols,
    double[] annualizedVolatility,
    double[][] correlation) {

  /** Tolerance for the unit-diagonal and symmetry checks. */
  private static final double TOLERANCE = 1e-6;

  /**
   * Rejects a structurally invalid model. Called by the consumer before the model is applied — a
   * malformed matrix that reached {@link PortfolioRiskModel} would produce a nonsense VaR rather
   * than an obvious failure.
   *
   * @throws IllegalArgumentException with a message naming the specific violation
   */
  public void validate() {
    if (symbols == null || symbols.isEmpty()) {
      throw new IllegalArgumentException("risk model has no symbols");
    }
    if (generatedAt == null) {
      throw new IllegalArgumentException("risk model has no generatedAt");
    }
    int n = symbols.size();
    if (annualizedVolatility == null || annualizedVolatility.length != n) {
      throw new IllegalArgumentException(
          "annualizedVolatility length "
              + (annualizedVolatility == null ? "null" : annualizedVolatility.length)
              + " does not match "
              + n
              + " symbols");
    }
    if (correlation == null || correlation.length != n) {
      throw new IllegalArgumentException(
          "correlation must have "
              + n
              + " rows, got "
              + (correlation == null ? "null" : correlation.length));
    }
    for (int i = 0; i < n; i++) {
      double vol = annualizedVolatility[i];
      if (!Double.isFinite(vol) || vol < 0) {
        throw new IllegalArgumentException(
            "annualizedVolatility[" + i + "] is not a finite non-negative number: " + vol);
      }
      double[] row = correlation[i];
      if (row == null || row.length != n) {
        throw new IllegalArgumentException("correlation row " + i + " must have " + n + " entries");
      }
      if (Math.abs(row[i] - 1.0) > TOLERANCE) {
        throw new IllegalArgumentException(
            "correlation diagonal at " + i + " is " + row[i] + ", expected 1.0");
      }
      for (int j = 0; j < n; j++) {
        double value = row[j];
        if (!Double.isFinite(value) || Math.abs(value) > 1.0 + TOLERANCE) {
          throw new IllegalArgumentException(
              "correlation[" + i + "][" + j + "] is out of range: " + value);
        }
        if (Math.abs(value - correlation[j][i]) > TOLERANCE) {
          throw new IllegalArgumentException(
              "correlation is not symmetric at (" + i + ", " + j + ")");
        }
      }
    }
    if (tradingDaysPerYear <= 0) {
      throw new IllegalArgumentException("tradingDaysPerYear must be positive");
    }
  }
}
