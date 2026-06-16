package com.mariaalpha.strategyengine.options;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

  public double price(OptionContract contract) {
    long start = System.nanoTime();
    double premium = pricer.price(contract);
    metrics.recordPricing(contract.type(), System.nanoTime() - start);
    return premium;
  }

  public Greeks greeks(OptionContract contract) {
    return greeksCalculator.compute(contract);
  }

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

  public record Priced(double price, Greeks greeks) {}
}
