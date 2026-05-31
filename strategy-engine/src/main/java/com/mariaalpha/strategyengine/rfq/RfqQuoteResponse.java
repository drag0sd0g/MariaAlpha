package com.mariaalpha.strategyengine.rfq;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code POST /api/rfq/quote}. The breakdown fields are stable for UI consumption
 * — the React RFQ panel reads them to show inventory / vol / ADV contributions next to the bid +
 * ask, so traders can sanity-check why a particular quote came out where it did.
 */
public record RfqQuoteResponse(
    UUID quoteId,
    String symbol,
    int quantity,
    BigDecimal marketMid,
    BigDecimal adjustedMid,
    BigDecimal bid,
    BigDecimal ask,
    Breakdown breakdown,
    Instant issuedAt,
    Instant expiresAt,
    long validForMs) {

  public static RfqQuoteResponse from(RfqQuote q, long validForMs) {
    return new RfqQuoteResponse(
        q.quoteId(),
        q.symbol(),
        q.quantity(),
        q.marketMid(),
        q.adjustedMid(),
        q.bid(),
        q.ask(),
        new Breakdown(
            q.inventoryNetQuantity(),
            q.inventoryNotionalUsd(),
            q.inventorySkewBps(),
            q.realizedVolBps(),
            q.volWideningBps(),
            q.advParticipationFraction(),
            q.advWideningBps(),
            q.baseHalfSpreadBps(),
            q.totalHalfSpreadBps(),
            q.advShares()),
        q.issuedAt(),
        q.expiresAt(),
        validForMs);
  }

  public record Breakdown(
      double inventoryNetQuantity,
      double inventoryNotionalUsd,
      double inventorySkewBps,
      double realizedVolBps,
      double volWideningBps,
      double advParticipationFraction,
      double advWideningBps,
      double baseHalfSpreadBps,
      double totalHalfSpreadBps,
      long advShares) {}
}
