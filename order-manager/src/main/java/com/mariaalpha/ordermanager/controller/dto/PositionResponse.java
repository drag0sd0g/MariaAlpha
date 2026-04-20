package com.mariaalpha.ordermanager.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mariaalpha.ordermanager.entity.PositionEntity;
import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PositionResponse(
    String symbol,
    BigDecimal netQuantity,
    BigDecimal avgEntryPrice,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    BigDecimal totalPnl,
    BigDecimal lastMarkPrice,
    Instant updatedAt) {

  public static PositionResponse of(PositionEntity p) {
    BigDecimal total = p.getRealizedPnl().add(p.getUnrealizedPnl());
    return new PositionResponse(
        p.getSymbol(),
        p.getNetQuantity(),
        p.getAvgEntryPrice(),
        p.getRealizedPnl(),
        p.getUnrealizedPnl(),
        total,
        p.getLastMarkPrice(),
        p.getUpdatedAt());
  }
}
