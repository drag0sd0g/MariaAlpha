package com.mariaalpha.strategyengine.options;

/**
 * Request body for {@code POST /api/options/implied-volatility}. Same shape as {@link
 * OptionPricingRequest} but replaces {@code volatility} with {@code marketPrice} — the observed
 * premium we want to invert.
 */
public record ImpliedVolatilityRequest(
    String symbol,
    double spot,
    double strike,
    double timeToExpiryYears,
    double riskFreeRate,
    double dividendYield,
    OptionType type,
    double marketPrice) {}
