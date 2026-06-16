package com.mariaalpha.strategyengine.options;

public record ImpliedVolatilityRequest(
    String symbol,
    double spot,
    double strike,
    double timeToExpiryYears,
    double riskFreeRate,
    double dividendYield,
    OptionType type,
    double marketPrice) {}
