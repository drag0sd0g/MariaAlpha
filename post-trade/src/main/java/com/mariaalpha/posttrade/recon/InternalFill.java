package com.mariaalpha.posttrade.recon;

import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
