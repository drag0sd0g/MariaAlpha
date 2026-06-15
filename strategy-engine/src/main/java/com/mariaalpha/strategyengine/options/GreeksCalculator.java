package com.mariaalpha.strategyengine.options;

import com.mariaalpha.strategyengine.options.BlackScholesPricer.DiscountedTerms;
import org.springframework.stereotype.Component;

@Component
public class GreeksCalculator {

  private final OptionsPricingConfig config;

  public GreeksCalculator(OptionsPricingConfig config) {
    this.config = config;
  }

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
