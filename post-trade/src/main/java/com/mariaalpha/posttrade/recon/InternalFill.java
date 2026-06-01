package com.mariaalpha.posttrade.recon;

import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal fill snapshot used by the reconciliation comparator. Built from order-manager's {@code
 * /api/orders/fills/by-date} endpoint, one row per fill (a single order may have many).
 */
public record InternalFill(
    UUID fillId,
    UUID orderId,
    String exchangeOrderId,
    String clientOrderId,
    String symbol,
    Side side,
    BigDecimal price,
    BigDecimal quantity,
    Instant filledAt) {}
