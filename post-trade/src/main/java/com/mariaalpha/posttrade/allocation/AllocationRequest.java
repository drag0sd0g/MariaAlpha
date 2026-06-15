package com.mariaalpha.posttrade.allocation;

import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.util.UUID;

public record AllocationRequest(
    UUID orderId,
    String symbol,
    Side side,
    BigDecimal parentFilledQuantity,
    BigDecimal parentAvgPrice,
    AllocationMethod method) {}
