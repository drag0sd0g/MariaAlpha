package com.mariaalpha.posttrade.controller.dto;

import com.mariaalpha.posttrade.allocation.AllocationMethod;
import com.mariaalpha.posttrade.entity.AllocationEntity;
import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Per-row allocation record returned by the {@code /api/allocations} REST surface. */
public record AllocationResponse(
    UUID allocationId,
    UUID orderId,
    String subAccount,
    String symbol,
    Side side,
    BigDecimal allocatedQuantity,
    BigDecimal allocatedAvgPrice,
    AllocationMethod allocationMethod,
    BigDecimal parentFilledQuantity,
    BigDecimal parentAvgPrice,
    Instant allocatedAt) {

  public static AllocationResponse of(AllocationEntity e) {
    return new AllocationResponse(
        e.getAllocationId(),
        e.getOrderId(),
        e.getSubAccount(),
        e.getSymbol(),
        e.getSide(),
        e.getAllocatedQuantity(),
        e.getAllocatedAvgPrice(),
        e.getAllocationMethod(),
        e.getParentFilledQuantity(),
        e.getParentAvgPrice(),
        e.getAllocatedAt());
  }
}
