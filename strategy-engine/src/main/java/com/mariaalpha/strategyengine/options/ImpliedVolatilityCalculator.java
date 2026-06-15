package com.mariaalpha.strategyengine.options;

import org.springframework.stereotype.Component;

@Component
public class ImpliedVolatilityCalculator {

  private static final double SEED_VOLATILITY = 0.20;

  private final BlackScholesPricer pricer;
  private final OptionsPricingConfig config;

  public ImpliedVolatilityCalculator(BlackScholesPricer pricer, OptionsPricingConfig config) {
    this.pricer = pricer;
    this.config = config;
  }

  public Result solve(
      double spot,
      double strike,
      double timeToExpiryYears,
      double riskFreeRate,
      double dividendYield,
      OptionType type,
      double marketPrice) {
    if (!(marketPrice > 0)) {
      throw new IllegalArgumentException("marketPrice must be > 0 (was " + marketPrice + ")");
    }
    double discountQ = Math.exp(-dividendYield * timeToExpiryYears);
    double discountR = Math.exp(-riskFreeRate * timeToExpiryYears);
    double forwardSpot = spot * discountQ;
    double presentStrike = strike * discountR;

    double lowerNoArb;
    double upperNoArb;
    switch (type) {
      case CALL -> {
        lowerNoArb = Math.max(0.0, forwardSpot - presentStrike);
        upperNoArb = forwardSpot;
      }
      case PUT -> {
        lowerNoArb = Math.max(0.0, presentStrike - forwardSpot);
        upperNoArb = presentStrike;
      }
      default -> throw new IllegalStateException("Unknown option type: " + type);
    }
    if (marketPrice <= lowerNoArb || marketPrice >= upperNoArb) {
      throw new IllegalArgumentException(
          String.format(
              "marketPrice %s outside no-arbitrage band (%s, %s) for %s",
              marketPrice, lowerNoArb, upperNoArb, type));
    }

    double sigma = SEED_VOLATILITY;
    for (int iteration = 1; iteration <= config.impliedVolMaxIterations(); iteration++) {
      OptionContract trial =
          new OptionContract(
              spot, strike, timeToExpiryYears, sigma, riskFreeRate, dividendYield, type);
      double modelPrice = pricer.price(trial);
      double residual = modelPrice - marketPrice;
      if (Math.abs(residual) <= config.impliedVolTolerance()) {
        return new Result(sigma, iteration, Method.NEWTON, residual);
      }
      BlackScholesPricer.DiscountedTerms terms = BlackScholesPricer.DiscountedTerms.from(trial);
      double vega = terms.discountedSpot() * NormalDistribution.pdf(terms.d1()) * terms.sqrtT();
      if (vega < 1.0e-8) {
        break;
      }
      double next = sigma - residual / vega;
      if (next <= config.impliedVolLowerBound() || next >= config.impliedVolUpperBound()) {
        break;
      }
      sigma = next;
    }
    return bisect(spot, strike, timeToExpiryYears, riskFreeRate, dividendYield, type, marketPrice);
  }

  private Result bisect(
      double spot,
      double strike,
      double timeToExpiryYears,
      double riskFreeRate,
      double dividendYield,
      OptionType type,
      double marketPrice) {
    double lo = config.impliedVolLowerBound();
    double hi = config.impliedVolUpperBound();
    double residualLo =
        priceAt(spot, strike, timeToExpiryYears, lo, riskFreeRate, dividendYield, type)
            - marketPrice;
    double residualHi =
        priceAt(spot, strike, timeToExpiryYears, hi, riskFreeRate, dividendYield, type)
            - marketPrice;
    if (residualLo * residualHi > 0) {
      throw new IllegalStateException(
          String.format(
              "marketPrice %s not bracketed by σ∈[%s, %s] (residuals %s / %s)",
              marketPrice, lo, hi, residualLo, residualHi));
    }
    double mid = 0.5 * (lo + hi);
    for (int iteration = 1; iteration <= config.impliedVolMaxIterations(); iteration++) {
      mid = 0.5 * (lo + hi);
      double residualMid =
          priceAt(spot, strike, timeToExpiryYears, mid, riskFreeRate, dividendYield, type)
              - marketPrice;
      if (Math.abs(residualMid) <= config.impliedVolTolerance()) {
        return new Result(mid, iteration, Method.BISECTION, residualMid);
      }
      if (residualLo * residualMid < 0) {
        hi = mid;
        residualHi = residualMid;
      } else {
        lo = mid;
        residualLo = residualMid;
      }
    }
    return new Result(
        mid,
        config.impliedVolMaxIterations(),
        Method.BISECTION,
        priceAt(spot, strike, timeToExpiryYears, mid, riskFreeRate, dividendYield, type)
            - marketPrice);
  }

  private double priceAt(
      double spot,
      double strike,
      double timeToExpiryYears,
      double sigma,
      double riskFreeRate,
      double dividendYield,
      OptionType type) {
    return pricer.price(
        new OptionContract(
            spot, strike, timeToExpiryYears, sigma, riskFreeRate, dividendYield, type));
  }

  public record Result(double impliedVolatility, int iterations, Method method, double residual) {}

  public enum Method {
    NEWTON,
    BISECTION
  }
}
