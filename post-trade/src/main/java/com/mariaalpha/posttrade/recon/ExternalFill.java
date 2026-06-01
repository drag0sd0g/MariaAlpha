package com.mariaalpha.posttrade.recon;

import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Venue-confirmed fill record used by the reconciliation comparator. Built from Alpaca's {@code
 * /v2/account/activities/FILL} response in {@link Mode#EXTERNAL}, or mirrored from internal fills
 * in {@link Mode#MIRROR}.
 */
public record ExternalFill(
    String externalFillId,
    String exchangeOrderId,
    String clientOrderId,
    String symbol,
    Side side,
    BigDecimal price,
    BigDecimal quantity,
    Instant filledAt) {}
