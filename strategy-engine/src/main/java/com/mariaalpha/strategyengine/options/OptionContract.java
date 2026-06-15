package com.mariaalpha.strategyengine.options;

public record OptionContract(
    double spot,
    double strike,
    double timeToExpiryYears,
    double volatility,
    double riskFreeRate,
    double dividendYield,
    OptionType type) {

  public OptionContract {
    if (!(spot > 0)) {
      throw new IllegalArgumentException("spot must be > 0 (was " + spot + ")");
    }
    if (!(strike > 0)) {
      throw new IllegalArgumentException("strike must be > 0 (was " + strike + ")");
    }
    if (!(timeToExpiryYears > 0)) {
      throw new IllegalArgumentException(
          "timeToExpiryYears must be > 0 (was " + timeToExpiryYears + ")");
    }
    if (!(volatility > 0)) {
      throw new IllegalArgumentException("volatility must be > 0 (was " + volatility + ")");
    }
    if (!(dividendYield >= 0)) {
      throw new IllegalArgumentException("dividendYield must be >= 0 (was " + dividendYield + ")");
    }
    if (type == null) {
      throw new IllegalArgumentException("type must be CALL or PUT");
    }
  }
}
