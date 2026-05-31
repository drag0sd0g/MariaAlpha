package com.mariaalpha.strategyengine.rfq;

/** Request body for {@code POST /api/rfq/quote}. */
public record RfqQuoteRequest(String symbol, int quantity) {}
