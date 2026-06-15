package com.mariaalpha.strategyengine.options;

final class NormalDistribution {

  private static final double INV_SQRT_TWO_PI = 1.0 / Math.sqrt(2.0 * Math.PI);

  private static final double A1 = 0.319381530;
  private static final double A2 = -0.356563782;
  private static final double A3 = 1.781477937;
  private static final double A4 = -1.821255978;
  private static final double A5 = 1.330274429;
  private static final double GAMMA = 0.2316419;

  private NormalDistribution() {}

  static double pdf(double x) {
    return INV_SQRT_TWO_PI * Math.exp(-0.5 * x * x);
  }

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
