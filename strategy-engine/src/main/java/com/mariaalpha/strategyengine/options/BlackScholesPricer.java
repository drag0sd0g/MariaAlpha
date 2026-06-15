package com.mariaalpha.strategyengine.options;

import org.springframework.stereotype.Component;

@Component
public class BlackScholesPricer {

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
