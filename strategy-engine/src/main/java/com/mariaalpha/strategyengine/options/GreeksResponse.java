package com.mariaalpha.strategyengine.options;

/** Response body for {@code POST /api/options/greeks}. */
public record GreeksResponse(String symbol, OptionType type, Greeks greeks) {

  static GreeksResponse of(OptionPricingRequest request, Greeks greeks) {
    return new GreeksResponse(request.symbol(), request.type(), greeks);
  }
}
