package com.mariaalpha.posttrade.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.posttrade.entity.ReconciliationBreakEntity;
import com.mariaalpha.posttrade.repository.ReconciliationBreakRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReconController.class)
class ReconControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ReconciliationBreakRepository repository;

  @Test
  void breaksReturnsEmptyArrayWhenNoneRecorded() throws Exception {
    when(repository.findByReconDateOrderBySeverityDesc(LocalDate.of(2026, 3, 24)))
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
    when(repository.findByReconDateOrderBySeverityDesc(LocalDate.of(2026, 3, 24)))
        .thenReturn(List.of(b));
    mockMvc
        .perform(get("/api/recon/breaks").param("date", "2026-03-24"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].breakType").value("MISSING_FILL"))
        .andExpect(jsonPath("$[0].severity").value("HIGH"));
  }

  @Test
  void summaryBucketsBySeverityAndType() throws Exception {
    var b1 = mk("MISSING_FILL", "HIGH");
    var b2 = mk("MISSING_FILL", "MEDIUM");
    var b3 = mk("PRICE_MISMATCH", "HIGH");
    when(repository.findByReconDateOrderBySeverityDesc(LocalDate.of(2026, 3, 24)))
        .thenReturn(List.of(b1, b2, b3));
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
  void runsListsRecentDates() throws Exception {
    when(repository.findRecentReconDates())
        .thenReturn(List.of(LocalDate.of(2026, 3, 24), LocalDate.of(2026, 3, 23)));
    mockMvc
        .perform(get("/api/recon/runs"))
        .andExpect(status().isOk())
        .andExpect(content().json("[\"2026-03-24\",\"2026-03-23\"]"));
  }

  @Test
  void breaksForOrderReturnsAllAcrossDates() throws Exception {
    var orderId = UUID.randomUUID();
    var b = mk("MISSING_FILL", "HIGH");
    b.setOrderId(orderId);
    when(repository.findByOrderIdOrderByReconDateDesc(orderId)).thenReturn(List.of(b));
    mockMvc
        .perform(get("/api/recon/breaks/order/" + orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].orderId").value(orderId.toString()));
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
}
