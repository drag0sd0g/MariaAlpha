package com.mariaalpha.strategyengine.rfq;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A two-way RFQ quote, including the per-component breakdown used to assemble it. The breakdown is
 * what makes inventory / volatility / ADV widening auditable — the UI surfaces it next to the
 * quote, and TCA replays it for the accepted-price reconstruction.
 */
public record RfqQuote(
    UUID quoteId,
    String symbol,
    int quantity,
    BigDecimal marketMid,
    BigDecimal adjustedMid,
    BigDecimal bid,
    BigDecimal ask,
    double inventoryNetQuantity,
    double inventoryNotionalUsd,
    double inventorySkewBps,
    double realizedVolBps,
    double volWideningBps,
    double advParticipationFraction,
    double advWideningBps,
    double baseHalfSpreadBps,
    double totalHalfSpreadBps,
    long advShares,
    Instant issuedAt,
    Instant expiresAt) {}
