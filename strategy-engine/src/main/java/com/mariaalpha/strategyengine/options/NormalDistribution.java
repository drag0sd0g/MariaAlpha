package com.mariaalpha.strategyengine.options;

/**
 * Standard normal CDF (Φ) and PDF (φ).
 *
 * <p>The CDF uses Abramowitz &amp; Stegun 26.2.17 — a five-term rational approximation accurate to
 * about 7.5×10⁻⁸ across the entire real line, which is more than enough for option pricing
 * (Black-Scholes prices are stable to ~10⁻⁵ at typical inputs and our integration tests target
 * 4-decimal agreement with textbook values).
 *
 * <p>Pulled into its own class so {@link BlackScholesPricer} and {@link GreeksCalculator} share
 * exactly one implementation, and so unit tests can exercise it in isolation.
 */
final class NormalDistribution {

  private static final double INV_SQRT_TWO_PI = 1.0 / Math.sqrt(2.0 * Math.PI);

  private static final double A1 = 0.319381530;
  private static final double A2 = -0.356563782;
  private static final double A3 = 1.781477937;
  private static final double A4 = -1.821255978;
  private static final double A5 = 1.330274429;
  private static final double GAMMA = 0.2316419;

  private NormalDistribution() {
    // utility
  }

  /** Standard normal probability density φ(x) = (1/√(2π)) · exp(-x²/2). */
  static double pdf(double x) {
    return INV_SQRT_TWO_PI * Math.exp(-0.5 * x * x);
  }

  /**
   * Standard normal cumulative distribution Φ(x). Uses the A&amp;S 26.2.17 rational approximation
   * for x ≥ 0 and the reflection Φ(-x) = 1 − Φ(x) for x &lt; 0.
   */
  static double cdf(double x) {
    if (Double.isNaN(x)) {
      return Double.NaN;
    }
    if (x == 0.0) {
      return 0.5;
    }
    double absX = Math.abs(x);
    double k = 1.0 / (1.0 + GAMMA * absX);
    double poly = k * (A1 + k * (A2 + k * (A3 + k * (A4 + k * A5))));
    double approx = 1.0 - pdf(absX) * poly;
    return x >= 0 ? approx : 1.0 - approx;
  }
}
