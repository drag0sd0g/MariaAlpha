package com.mariaalpha.posttrade.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReconSummaryResponse(
    LocalDate reconDate,
    int totalBreaks,
    Map<String, Integer> bySeverity,
    Map<String, Integer> byBreakType,
    ReconRunResponse run) {}
