package com.mariaalpha.posttrade.controller;

import com.mariaalpha.posttrade.controller.dto.ReconBreakResponse;
import com.mariaalpha.posttrade.controller.dto.ReconSummaryResponse;
import com.mariaalpha.posttrade.repository.ReconciliationBreakRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only reconciliation API consumed by the UI Reconciliation page (issue 2.5.4).
 *
 * <p>The reconciliation engine itself (issue 2.6.1) is on a later milestone. The endpoints here
 * already work — they just return empty payloads until 2.6.1 starts populating the {@code
 * reconciliation_breaks} table. The contract is intentionally future-proof: the same endpoints
 * serve real data once 2.6.1 ships, so the UI page doesn't need a follow-up change.
 */
@RestController
@RequestMapping("/api/recon")
public class ReconController {

  private final ReconciliationBreakRepository repository;

  public ReconController(ReconciliationBreakRepository repository) {
    this.repository = repository;
  }

  /** List reconciliation breaks for a date (defaults to today). */
  @GetMapping("/breaks")
  public List<ReconBreakResponse> breaks(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    var target = date != null ? date : LocalDate.now();
    return repository.findByReconDateOrderBySeverityDesc(target).stream()
        .map(ReconBreakResponse::of)
        .toList();
  }

  /** Per-date summary: total breaks bucketed by severity and break type. */
  @GetMapping("/summary")
  public ReconSummaryResponse summary(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    var target = date != null ? date : LocalDate.now();
    var rows = repository.findByReconDateOrderBySeverityDesc(target);
    var bySeverity = new HashMap<String, Integer>();
    var byBreakType = new HashMap<String, Integer>();
    for (var row : rows) {
      bySeverity.merge(row.getSeverity(), 1, Integer::sum);
      byBreakType.merge(row.getBreakType(), 1, Integer::sum);
    }
    return new ReconSummaryResponse(target, rows.size(), bySeverity, byBreakType);
  }

  /** Most recent reconciliation dates that produced at least one break. */
  @GetMapping("/runs")
  public List<LocalDate> runs() {
    return repository.findRecentReconDates();
  }

  /** All breaks recorded against a given order id, across dates. */
  @GetMapping("/breaks/order/{orderId}")
  public List<ReconBreakResponse> breaksForOrder(@PathVariable UUID orderId) {
    return repository.findByOrderIdOrderByReconDateDesc(orderId).stream()
        .map(ReconBreakResponse::of)
        .toList();
  }
}
