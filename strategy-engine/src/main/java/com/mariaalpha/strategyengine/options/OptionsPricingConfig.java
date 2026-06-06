package com.mariaalpha.strategyengine.options;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the options pricing module (3.2.1 / 3.2.2).
 *
 * <ul>
 *   <li>{@link #thetaDayCount()} — denominator used to convert annualised Theta into a per-day
 *       number. Two conventions in industry use: {@code 365} (calendar) and {@code 252} (trading).
 *       Defaults to 365 so the UI matches Bloomberg's OVME.
 *   <li>{@link #impliedVolMaxIterations()} — Newton-Raphson cap before the implied-vol solver gives
 *       up and falls back to bisection. Defaults to 100, which leaves plenty of headroom past the
 *       quadratic convergence point.
 *   <li>{@link #impliedVolTolerance()} — absolute tolerance on the price residual for the
 *       implied-vol solver. Defaults to {@code 1e-6} dollars; below that, floating-point noise in
 *       {@code NormalDistribution.cdf} dominates the residual.
 *   <li>{@link #impliedVolLowerBound()} / {@link #impliedVolUpperBound()} — bracket for the
 *       bisection fallback. {@code [0.0001, 5.0]} spans &lt;1%/yr to 500%/yr — outside that range
 *       an option is either pinned to intrinsic value or the inputs are wrong.
 * </ul>
 */
@ConfigurationProperties(prefix = "strategy-engine.options")
public record OptionsPricingConfig(
    double thetaDayCount,
    int impliedVolMaxIterations,
    double impliedVolTolerance,
    double impliedVolLowerBound,
    double impliedVolUpperBound) {

  public OptionsPricingConfig {
    if (thetaDayCount <= 0) {
      thetaDayCount = 365.0;
    }
    if (impliedVolMaxIterations <= 0) {
      impliedVolMaxIterations = 100;
    }
    if (impliedVolTolerance <= 0) {
      impliedVolTolerance = 1.0e-6;
    }
    if (impliedVolLowerBound <= 0) {
      impliedVolLowerBound = 1.0e-4;
    }
    if (impliedVolUpperBound <= impliedVolLowerBound) {
      impliedVolUpperBound = 5.0;
    }
  }
}
