package com.mariaalpha.strategyengine.options;

public record OptionPricingResponse(String symbol, OptionType type, double price, Greeks greeks) {

  static OptionPricingResponse of(OptionPricingRequest request, double price, Greeks greeks) {
    return new OptionPricingResponse(request.symbol(), request.type(), price, greeks);
  }
}
