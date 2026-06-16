package com.mariaalpha.strategyengine.options;

public record GreeksResponse(String symbol, OptionType type, Greeks greeks) {

  static GreeksResponse of(OptionPricingRequest request, Greeks greeks) {
    return new GreeksResponse(request.symbol(), request.type(), greeks);
  }
}
