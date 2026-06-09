package com.mariaalpha.posttrade.controller.dto;

import com.mariaalpha.posttrade.allocation.AllocationMethod;
import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for {@code POST /api/allocations/run} (roadmap 3.4.2). {@code method} is nullable —
 * server falls back to the configured default when omitted.
 */
public record AllocationRequestDto(
    UUID orderId,
    String symbol,
    Side side,
    BigDecimal parentFilledQuantity,
    BigDecimal parentAvgPrice,
    AllocationMethod method) {}
