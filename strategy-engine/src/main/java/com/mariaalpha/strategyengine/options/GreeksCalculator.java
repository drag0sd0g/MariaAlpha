package com.mariaalpha.strategyengine.options;

import com.mariaalpha.strategyengine.options.BlackScholesPricer.DiscountedTerms;
import org.springframework.stereotype.Component;

/**
 * Analytic first-order Black-Scholes-Merton Greeks (roadmap 3.2.2).
 *
 * <p>All formulas use the dividend-adjusted variant (continuous yield {@code q}). Vega and Rho are
 * reported per 1-percentage-point move in their underlying input so the displayed numbers match the
 * scale traders expect. Theta is reported per day using the {@link
 * OptionsPricingConfig#thetaDayCount()} convention.
 *
 * <pre>
 *   Δ_call = e^(−q·T)·Φ(d1)
 *   Δ_put  = e^(−q·T)·(Φ(d1) − 1)
 *   Γ      = e^(−q·T)·φ(d1) / (S·σ·√T)
 *   vega   = S·e^(−q·T)·φ(d1)·√T            (annualised, then ÷100 → per-1%-vol)
 *   Θ_call = −S·e^(−q·T)·φ(d1)·σ/(2·√T)             (÷ thetaDayCount → /day)
 *            − r·K·e^(−r·T)·Φ(d2) + q·S·e^(−q·T)·Φ(d1)
 *   Θ_put  = −S·e^(−q·T)·φ(d1)·σ/(2·√T)
 *            + r·K·e^(−r·T)·Φ(−d2) − q·S·e^(−q·T)·Φ(−d1)
 *   ρ_call =  K·T·e^(−r·T)·Φ(d2)             (annualised, then ÷100 → per-1%-rate)
 *   ρ_put  = −K·T·e^(−r·T)·Φ(−d2)
 * </pre>
 */
@Component
public class GreeksCalculator {

  private final OptionsPricingConfig config;

  public GreeksCalculator(OptionsPricingConfig config) {
    this.config = config;
  }

  /** Compute all five Greeks for {@code contract}. */
  public Greeks compute(OptionContract contract) {
    DiscountedTerms terms = DiscountedTerms.from(contract);
    double s = contract.spot();
    double k = contract.strike();
    double t = contract.timeToExpiryYears();
    double sigma = contract.volatility();
    double r = contract.riskFreeRate();
    double q = contract.dividendYield();

    double phiD1 = NormalDistribution.pdf(terms.d1());
    double cdfD1 = NormalDistribution.cdf(terms.d1());
    double cdfD2 = NormalDistribution.cdf(terms.d2());

    double delta;
    double thetaAnnual;
    double rhoAnnual;
    switch (contract.type()) {
      case CALL -> {
        delta = terms.discountQ() * cdfD1;
        thetaAnnual =
            -terms.discountedSpot() * phiD1 * sigma / (2.0 * terms.sqrtT())
                - r * terms.discountedStrike() * cdfD2
                + q * terms.discountedSpot() * cdfD1;
        rhoAnnual = k * t * terms.discountR() * cdfD2;
      }
      case PUT -> {
        delta = terms.discountQ() * (cdfD1 - 1.0);
        double cdfMinusD1 = NormalDistribution.cdf(-terms.d1());
        double cdfMinusD2 = NormalDistribution.cdf(-terms.d2());
        thetaAnnual =
            -terms.discountedSpot() * phiD1 * sigma / (2.0 * terms.sqrtT())
                + r * terms.discountedStrike() * cdfMinusD2
                - q * terms.discountedSpot() * cdfMinusD1;
        rhoAnnual = -k * t * terms.discountR() * cdfMinusD2;
      }
      default -> throw new IllegalStateException("Unknown option type: " + contract.type());
    }

    double gamma = terms.discountQ() * phiD1 / (s * sigma * terms.sqrtT());
    double vegaAnnual = terms.discountedSpot() * phiD1 * terms.sqrtT();

    double vegaPerOnePct = vegaAnnual / 100.0;
    double thetaPerDay = thetaAnnual / config.thetaDayCount();
    double rhoPerOnePct = rhoAnnual / 100.0;
    return new Greeks(delta, gamma, vegaPerOnePct, thetaPerDay, rhoPerOnePct);
  }
}
