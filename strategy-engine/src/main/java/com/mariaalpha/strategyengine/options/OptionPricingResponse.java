package com.mariaalpha.strategyengine.options;

/**
 * Response body for {@code POST /api/options/price} — the theoretical premium plus the full Greek
 * bundle. The echoed {@code symbol} / {@code type} keep the response self-describing so the UI can
 * render a row of contracts side-by-side without holding onto the request.
 */
public record OptionPricingResponse(String symbol, OptionType type, double price, Greeks greeks) {

  static OptionPricingResponse of(OptionPricingRequest request, double price, Greeks greeks) {
    return new OptionPricingResponse(request.symbol(), request.type(), price, greeks);
  }
}
