package com.mariaalpha.strategyengine.options;

public record OptionPricingRequest(
    String symbol,
    double spot,
    double strike,
    double timeToExpiryYears,
    double volatility,
    double riskFreeRate,
    double dividendYield,
    OptionType type) {

  public OptionContract toContract() {
    return new OptionContract(
        spot, strike, timeToExpiryYears, volatility, riskFreeRate, dividendYield, type);
  }
}
