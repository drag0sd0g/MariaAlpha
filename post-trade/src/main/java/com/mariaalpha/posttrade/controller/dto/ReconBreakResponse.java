package com.mariaalpha.posttrade.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mariaalpha.posttrade.entity.ReconciliationBreakEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReconBreakResponse(
    UUID breakId,
    LocalDate reconDate,
    UUID orderId,
    String breakType,
    String severity,
    String resolution,
    String symbol,
    String description,
    BigDecimal internalQty,
    BigDecimal externalQty,
    BigDecimal internalPrice,
    BigDecimal externalPrice,
    BigDecimal notional,
    Instant createdAt) {

  public static ReconBreakResponse of(ReconciliationBreakEntity e) {
    return new ReconBreakResponse(
        e.getBreakId(),
        e.getReconDate(),
        e.getOrderId(),
        e.getBreakType(),
        e.getSeverity(),
        e.getResolution(),
        e.getSymbol(),
        e.getDescription(),
        e.getInternalQty(),
        e.getExternalQty(),
        e.getInternalPrice(),
        e.getExternalPrice(),
        e.getNotional(),
        e.getCreatedAt());
  }
}
