package com.mariaalpha.posttrade.controller.dto;

import com.mariaalpha.posttrade.allocation.AllocationMethod;
import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.util.UUID;

public record AllocationRequestDto(
    UUID orderId,
    String symbol,
    Side side,
    BigDecimal parentFilledQuantity,
    BigDecimal parentAvgPrice,
    AllocationMethod method) {}
