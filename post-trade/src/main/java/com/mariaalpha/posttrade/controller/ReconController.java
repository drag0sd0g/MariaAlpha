package com.mariaalpha.posttrade.controller;

import com.mariaalpha.posttrade.controller.dto.ReconBreakResponse;
import com.mariaalpha.posttrade.controller.dto.ReconRunResponse;
import com.mariaalpha.posttrade.controller.dto.ReconSummaryResponse;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Source;
import com.mariaalpha.posttrade.recon.EodReconciliationService;
import com.mariaalpha.posttrade.repository.ReconciliationBreakRepository;
import com.mariaalpha.posttrade.repository.ReconciliationRunRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read + write reconciliation API consumed by the UI Reconciliation page (issue 2.5.4) and any
 * automation that needs to trigger an ad-hoc run. The {@code POST /api/recon/run} endpoint runs
 * synchronously — fast enough for the simulated stack, and useful in production for re-running
 * after a known-bad earlier attempt.
 */
@RestController
@RequestMapping("/api/recon")
public class ReconController {

  private final ReconciliationBreakRepository breakRepository;
  private final ReconciliationRunRepository runRepository;
  private final EodReconciliationService service;

  public ReconController(
      ReconciliationBreakRepository breakRepository,
      ReconciliationRunRepository runRepository,
      EodReconciliationService service) {
    this.breakRepository = breakRepository;
    this.runRepository = runRepository;
    this.service = service;
  }

  /** List reconciliation breaks for a date (defaults to today). */
  @GetMapping("/breaks")
  public List<ReconBreakResponse> breaks(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    var target = date != null ? date : LocalDate.now();
    return breakRepository.findByReconDateOrderBySeverityDesc(target).stream()
        .map(ReconBreakResponse::of)
        .toList();
  }

  /** Per-date summary plus the matching run record (if any) so the UI can render run status. */
  @GetMapping("/summary")
  public ReconSummaryResponse summary(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    var target = date != null ? date : LocalDate.now();
    var rows = breakRepository.findByReconDateOrderBySeverityDesc(target);
    var bySeverity = new HashMap<String, Integer>();
    var byBreakType = new HashMap<String, Integer>();
    for (var row : rows) {
      bySeverity.merge(row.getSeverity(), 1, Integer::sum);
      byBreakType.merge(row.getBreakType(), 1, Integer::sum);
    }
    var run = runRepository.findByReconDate(target).map(ReconRunResponse::of).orElse(null);
    return new ReconSummaryResponse(target, rows.size(), bySeverity, byBreakType, run);
  }

  /** Most recent reconciliation runs (regardless of whether they produced breaks). */
  @GetMapping("/runs")
  public List<ReconRunResponse> runs(@RequestParam(defaultValue = "30") int limit) {
    int clamped = Math.min(Math.max(limit, 1), 365);
    return runRepository.findRecent(PageRequest.of(0, clamped)).stream()
        .map(ReconRunResponse::of)
        .toList();
  }

  /** All breaks recorded against a given order id, across dates. */
  @GetMapping("/breaks/order/{orderId}")
  public List<ReconBreakResponse> breaksForOrder(@PathVariable UUID orderId) {
    return breakRepository.findByOrderIdOrderByReconDateDesc(orderId).stream()
        .map(ReconBreakResponse::of)
        .toList();
  }

  /** Trigger an EOD reconciliation run for the given date (defaults to today). Synchronous. */
  @PostMapping("/run")
  public ReconRunResponse run(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    var target = date != null ? date : LocalDate.now();
    return ReconRunResponse.of(service.runForDate(target, Source.MANUAL));
  }
}
