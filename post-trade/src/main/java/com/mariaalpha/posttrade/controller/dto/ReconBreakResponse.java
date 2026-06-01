package com.mariaalpha.posttrade.controller.dto;

import com.mariaalpha.posttrade.entity.ReconciliationBreakEntity;
import java.time.LocalDate;
import java.util.UUID;

public record ReconBreakResponse(
    UUID breakId,
    LocalDate reconDate,
    UUID orderId,
    String breakType,
    String severity,
    String resolution) {

  public static ReconBreakResponse of(ReconciliationBreakEntity e) {
    return new ReconBreakResponse(
        e.getBreakId(),
        e.getReconDate(),
        e.getOrderId(),
        e.getBreakType(),
        e.getSeverity(),
        e.getResolution());
  }
}
