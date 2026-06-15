package com.mariaalpha.strategyengine.options;

public record ImpliedVolatilityResponse(
    String symbol,
    OptionType type,
    double impliedVolatility,
    int iterations,
    ImpliedVolatilityCalculator.Method method,
    double residual) {

  static ImpliedVolatilityResponse of(
      ImpliedVolatilityRequest request, ImpliedVolatilityCalculator.Result result) {
    return new ImpliedVolatilityResponse(
        request.symbol(),
        request.type(),
        result.impliedVolatility(),
        result.iterations(),
        result.method(),
        result.residual());
  }
}
