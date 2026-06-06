package com.mariaalpha.strategyengine.options;

/**
 * Request body for {@code POST /api/options/price} and {@code POST /api/options/greeks}. Mirrors
 * {@link OptionContract} one-for-one — kept as a separate record so the controller can stay free of
 * the constructor-time validation thrown by {@link OptionContract}, and so future request fields
 * (e.g. {@code symbol}, {@code contractSize}) can be added without disturbing the math.
 */
public record OptionPricingRequest(
    String symbol,
    double spot,
    double strike,
    double timeToExpiryYears,
    double volatility,
    double riskFreeRate,
    double dividendYield,
    OptionType type) {

  /** Convert to the math-layer {@link OptionContract}. */
  public OptionContract toContract() {
    return new OptionContract(
        spot, strike, timeToExpiryYears, volatility, riskFreeRate, dividendYield, type);
  }
}
