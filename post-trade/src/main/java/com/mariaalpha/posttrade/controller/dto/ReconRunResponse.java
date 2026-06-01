package com.mariaalpha.posttrade.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReconRunResponse(
    UUID runId,
    LocalDate reconDate,
    String status,
    String source,
    Instant startedAt,
    Instant finishedAt,
    Integer internalFillsCount,
    Integer externalFillsCount,
    Integer breaksCount,
    String errorMessage) {

  public static ReconRunResponse of(ReconciliationRunEntity e) {
    return new ReconRunResponse(
        e.getRunId(),
        e.getReconDate(),
        e.getStatus(),
        e.getSource(),
        e.getStartedAt(),
        e.getFinishedAt(),
        e.getInternalFillsCount(),
        e.getExternalFillsCount(),
        e.getBreaksCount(),
        e.getErrorMessage());
  }
}
