package com.mariaalpha.posttrade.controller.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * Per-date reconciliation summary consumed by the UI Reconciliation page (issue 2.5.4). Until the
 * EOD reconciliation engine (issue 2.6.1) lands, this is computed off the persisted {@code
 * reconciliation_breaks} table and will report 0 matched / 0 breaks on dates with no rows.
 */
public record ReconSummaryResponse(
    LocalDate reconDate,
    int totalBreaks,
    Map<String, Integer> bySeverity,
    Map<String, Integer> byBreakType) {}
