package com.mariaalpha.strategyengine.options;

import org.springframework.stereotype.Component;

/**
 * Black-Scholes-Merton European option pricer with a continuous dividend yield (FR roadmap 3.2.1).
 *
 * <p>The standard generalised formulas:
 *
 * <pre>
 *   d1 = (ln(S/K) + (r − q + σ²/2)·T) / (σ·√T)
 *   d2 = d1 − σ·√T
 *   Call = S·e^(−q·T)·Φ(d1) − K·e^(−r·T)·Φ(d2)
 *   Put  = K·e^(−r·T)·Φ(−d2) − S·e^(−q·T)·Φ(−d1)
 * </pre>
 *
 * <p>Setting {@code q = 0} recovers the original Black-Scholes equity formulas. The result is the
 * undiscounted theoretical fair value of one option contract on one unit of underlying — multiply
 * by the contract size (typically 100 shares) at the caller for a dollar premium.
 *
 * <p>The class is pure: it holds no state and is safe to call concurrently. It is registered as a
 * Spring {@link Component} so {@link OptionPricingService} and {@link GreeksCalculator} can be
 * autowired with it; the math is also reachable from tests via the public {@link #price} method.
 */
@Component
public class BlackScholesPricer {

  /** Theoretical fair value of {@code contract}. */
  public double price(OptionContract contract) {
    DiscountedTerms terms = DiscountedTerms.from(contract);
    return switch (contract.type()) {
      case CALL ->
          terms.discountedSpot() * NormalDistribution.cdf(terms.d1())
              - terms.discountedStrike() * NormalDistribution.cdf(terms.d2());
      case PUT ->
          terms.discountedStrike() * NormalDistribution.cdf(-terms.d2())
              - terms.discountedSpot() * NormalDistribution.cdf(-terms.d1());
    };
  }

  /**
   * Bundle of intermediate Black-Scholes terms shared by the pricer and {@link GreeksCalculator}.
   *
   * <p>Computing these once per contract keeps the Greek formulas readable and avoids recomputing
   * {@code √T} or {@code e^(−q·T)} in every Greek getter.
   */
  record DiscountedTerms(
      double d1,
      double d2,
      double sqrtT,
      double discountedSpot,
      double discountedStrike,
      double discountQ,
      double discountR) {

    static DiscountedTerms from(OptionContract contract) {
      double s = contract.spot();
      double k = contract.strike();
      double t = contract.timeToExpiryYears();
      double sigma = contract.volatility();
      double r = contract.riskFreeRate();
      double q = contract.dividendYield();

      double sqrtT = Math.sqrt(t);
      double sigmaSqrtT = sigma * sqrtT;
      double d1 = (Math.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / sigmaSqrtT;
      double d2 = d1 - sigmaSqrtT;
      double discountQ = Math.exp(-q * t);
      double discountR = Math.exp(-r * t);
      double discountedSpot = s * discountQ;
      double discountedStrike = k * discountR;
      return new DiscountedTerms(
          d1, d2, sqrtT, discountedSpot, discountedStrike, discountQ, discountR);
    }
  }
}
