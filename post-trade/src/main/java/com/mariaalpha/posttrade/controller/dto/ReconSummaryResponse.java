package com.mariaalpha.posttrade.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.Map;

/**
 * Per-date reconciliation summary consumed by the UI Reconciliation page (issue 2.5.4). The {@code
 * run} field surfaces the matching {@link com.mariaalpha.posttrade.entity.ReconciliationRunEntity}
 * row so the UI can render "ran clean" (run=SUCCESS, totalBreaks=0) vs. "never ran" (run=null) vs.
 * "ran with breaks".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReconSummaryResponse(
    LocalDate reconDate,
    int totalBreaks,
    Map<String, Integer> bySeverity,
    Map<String, Integer> byBreakType,
    ReconRunResponse run) {}
