package com.mariaalpha.strategyengine.options;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Façade that bundles {@link BlackScholesPricer} + {@link GreeksCalculator} into a single call so
 * the REST controller, future option strategies, and risk checks can ask for "everything I need
 * about this contract" in one shot.
 */
@Component
public class OptionPricingService {

  private static final Logger LOG = LoggerFactory.getLogger(OptionPricingService.class);

  private final BlackScholesPricer pricer;
  private final GreeksCalculator greeksCalculator;
  private final OptionsMetrics metrics;

  public OptionPricingService(
      BlackScholesPricer pricer, GreeksCalculator greeksCalculator, OptionsMetrics metrics) {
    this.pricer = pricer;
    this.greeksCalculator = greeksCalculator;
    this.metrics = metrics;
  }

  /** Theoretical fair value of {@code contract}. */
  public double price(OptionContract contract) {
    long start = System.nanoTime();
    double premium = pricer.price(contract);
    metrics.recordPricing(contract.type(), System.nanoTime() - start);
    return premium;
  }

  /** Five-Greek sensitivity bundle for {@code contract}. */
  public Greeks greeks(OptionContract contract) {
    return greeksCalculator.compute(contract);
  }

  /** Combined price + Greeks in one pass. */
  public Priced priceWithGreeks(OptionContract contract) {
    long start = System.nanoTime();
    double premium = pricer.price(contract);
    Greeks greeks = greeksCalculator.compute(contract);
    metrics.recordPricing(contract.type(), System.nanoTime() - start);
    LOG.debug(
        "Priced {} {}/{} T={} σ={} → premium={} Δ={} Γ={}",
        contract.type(),
        contract.spot(),
        contract.strike(),
        contract.timeToExpiryYears(),
        contract.volatility(),
        premium,
        greeks.delta(),
        greeks.gamma());
    return new Priced(premium, greeks);
  }

  /** Result of {@link #priceWithGreeks(OptionContract)}. */
  public record Priced(double price, Greeks greeks) {}
}
