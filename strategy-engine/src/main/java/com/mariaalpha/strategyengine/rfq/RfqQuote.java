package com.mariaalpha.strategyengine.rfq;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
