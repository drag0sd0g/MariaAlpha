package com.mariaalpha.posttrade.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.posttrade.entity.ReconciliationBreakEntity;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Source;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Status;
import com.mariaalpha.posttrade.recon.EodReconciliationService;
import com.mariaalpha.posttrade.repository.ReconciliationBreakRepository;
import com.mariaalpha.posttrade.repository.ReconciliationRunRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReconController.class)
class ReconControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ReconciliationBreakRepository breakRepository;
  @MockitoBean private ReconciliationRunRepository runRepository;
  @MockitoBean private EodReconciliationService service;

  @Test
  void breaksReturnsEmptyArrayWhenNoneRecorded() throws Exception {
    when(breakRepository.findByReconDateOrderBySeverityDesc(LocalDate.of(2026, 3, 24)))
        .thenReturn(List.of());
    mockMvc
        .perform(get("/api/recon/breaks").param("date", "2026-03-24"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void breaksSerializesEntity() throws Exception {
    var b = new ReconciliationBreakEntity();
    b.setBreakId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    b.setReconDate(LocalDate.of(2026, 3, 24));
    b.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000099"));
    b.setBreakType("MISSING_FILL");
    b.setSeverity("HIGH");
    b.setResolution("PENDING");
    b.setSymbol("AAPL");
    b.setDescription("desc");
    when(breakRepository.findByReconDateOrderBySeverityDesc(LocalDate.of(2026, 3, 24)))
        .thenReturn(List.of(b));
    mockMvc
        .perform(get("/api/recon/breaks").param("date", "2026-03-24"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].breakType").value("MISSING_FILL"))
        .andExpect(jsonPath("$[0].severity").value("HIGH"))
        .andExpect(jsonPath("$[0].symbol").value("AAPL"))
        .andExpect(jsonPath("$[0].description").value("desc"));
  }

  @Test
  void summaryBucketsBySeverityAndType() throws Exception {
    var b1 = mk("MISSING_FILL", "HIGH");
    var b2 = mk("MISSING_FILL", "MEDIUM");
    var b3 = mk("PRICE_MISMATCH", "HIGH");
    when(breakRepository.findByReconDateOrderBySeverityDesc(LocalDate.of(2026, 3, 24)))
        .thenReturn(List.of(b1, b2, b3));
    when(runRepository.findByReconDate(LocalDate.of(2026, 3, 24))).thenReturn(Optional.empty());
    mockMvc
        .perform(get("/api/recon/summary").param("date", "2026-03-24"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalBreaks").value(3))
        .andExpect(jsonPath("$.bySeverity.HIGH").value(2))
        .andExpect(jsonPath("$.bySeverity.MEDIUM").value(1))
        .andExpect(jsonPath("$.byBreakType.MISSING_FILL").value(2))
        .andExpect(jsonPath("$.byBreakType.PRICE_MISMATCH").value(1));
  }

  @Test
  void summaryIncludesRunRecordWhenPresent() throws Exception {
    when(breakRepository.findByReconDateOrderBySeverityDesc(LocalDate.of(2026, 3, 24)))
        .thenReturn(List.of());
    var run = freshRun(LocalDate.of(2026, 3, 24), Status.SUCCESS, 12, 12, 0);
    when(runRepository.findByReconDate(LocalDate.of(2026, 3, 24))).thenReturn(Optional.of(run));
    mockMvc
        .perform(get("/api/recon/summary").param("date", "2026-03-24"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalBreaks").value(0))
        .andExpect(jsonPath("$.run.status").value("SUCCESS"))
        .andExpect(jsonPath("$.run.internalFillsCount").value(12))
        .andExpect(jsonPath("$.run.externalFillsCount").value(12))
        .andExpect(jsonPath("$.run.breaksCount").value(0));
  }

  @Test
  void runsListsRecentRuns() throws Exception {
    var r1 = freshRun(LocalDate.of(2026, 3, 24), Status.SUCCESS, 10, 10, 0);
    var r2 = freshRun(LocalDate.of(2026, 3, 23), Status.SUCCESS, 8, 8, 1);
    when(runRepository.findRecent(any(Pageable.class))).thenReturn(List.of(r1, r2));
    mockMvc
        .perform(get("/api/recon/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].reconDate").value("2026-03-24"))
        .andExpect(jsonPath("$[1].reconDate").value("2026-03-23"));
  }

  @Test
  void breaksForOrderReturnsAllAcrossDates() throws Exception {
    var orderId = UUID.randomUUID();
    var b = mk("MISSING_FILL", "HIGH");
    b.setOrderId(orderId);
    when(breakRepository.findByOrderIdOrderByReconDateDesc(orderId)).thenReturn(List.of(b));
    mockMvc
        .perform(get("/api/recon/breaks/order/" + orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].orderId").value(orderId.toString()));
  }

  @Test
  void runEndpointInvokesServiceAndReturnsRunRecord() throws Exception {
    var run = freshRun(LocalDate.of(2026, 3, 24), Status.SUCCESS, 5, 5, 0);
    run.setSource(Source.MANUAL.name());
    when(service.runForDate(eq(LocalDate.of(2026, 3, 24)), eq(Source.MANUAL))).thenReturn(run);
    mockMvc
        .perform(post("/api/recon/run").param("date", "2026-03-24"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reconDate").value("2026-03-24"))
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.source").value("MANUAL"))
        .andExpect(jsonPath("$.breaksCount").value(0));
  }

  private static ReconciliationBreakEntity mk(String breakType, String severity) {
    var b = new ReconciliationBreakEntity();
    b.setBreakId(UUID.randomUUID());
    b.setReconDate(LocalDate.of(2026, 3, 24));
    b.setOrderId(UUID.randomUUID());
    b.setBreakType(breakType);
    b.setSeverity(severity);
    return b;
  }

  private static ReconciliationRunEntity freshRun(
      LocalDate date, Status status, int internal, int external, int breaks) {
    var r = new ReconciliationRunEntity();
    r.setRunId(UUID.randomUUID());
    r.setReconDate(date);
    r.setStatus(status.name());
    r.setSource(Source.SCHEDULED.name());
    r.setStartedAt(Instant.parse("2026-03-24T22:00:00Z"));
    r.setFinishedAt(Instant.parse("2026-03-24T22:00:05Z"));
    r.setInternalFillsCount(internal);
    r.setExternalFillsCount(external);
    r.setBreaksCount(breaks);
    return r;
  }
}
