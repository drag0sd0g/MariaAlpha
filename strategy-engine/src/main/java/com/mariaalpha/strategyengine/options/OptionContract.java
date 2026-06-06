package com.mariaalpha.strategyengine.options;

/**
 * Inputs to the Black-Scholes-Merton model for a European option on a single underlying with a
 * continuous dividend yield.
 *
 * <ul>
 *   <li>{@code spot} — current price of the underlying (S), &gt; 0.
 *   <li>{@code strike} — exercise price (K), &gt; 0.
 *   <li>{@code timeToExpiryYears} — calendar-time to expiry in years (T), &gt; 0. e.g. 0.25 for a
 *       three-month option.
 *   <li>{@code volatility} — annualised volatility of log-returns (σ), &gt; 0. 0.25 means 25%/yr.
 *   <li>{@code riskFreeRate} — annualised continuously-compounded risk-free rate (r). Allowed to be
 *       negative; e.g. 0.05 for 5%.
 *   <li>{@code dividendYield} — annualised continuous dividend yield (q), &ge; 0. Use 0 for
 *       non-dividend-paying equities.
 *   <li>{@code type} — {@link OptionType#CALL} or {@link OptionType#PUT}.
 * </ul>
 *
 * <p>The compact constructor rejects non-positive values for {@code spot}, {@code strike}, {@code
 * timeToExpiryYears}, and {@code volatility}, and a negative {@code dividendYield} — the rest of
 * the pipeline can therefore trust the contract.
 */
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
