package com.mariaalpha.ordermanager.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.ordermanager.controller.dto.PortfolioSummaryResponse;
import com.mariaalpha.ordermanager.service.PortfolioService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PortfolioController.class)
class PortfolioControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private PortfolioService portfolioService;

  @Test
  void summaryReturnsAggregatedPortfolio() throws Exception {
    var resp =
        new PortfolioSummaryResponse(
            new BigDecimal("1005000"),
            new BigDecimal("990000"),
            new BigDecimal("15000"),
            new BigDecimal("15000"),
            new BigDecimal("0"),
            new BigDecimal("1000"),
            new BigDecimal("1000"),
            1,
            Instant.now());
    when(portfolioService.summary()).thenReturn(resp);

    mockMvc
        .perform(get("/api/portfolio/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalValue").value(1005000))
        .andExpect(jsonPath("$.cashBalance").value(990000))
        .andExpect(jsonPath("$.grossExposure").value(15000))
        .andExpect(jsonPath("$.netExposure").value(15000))
        .andExpect(jsonPath("$.openPositions").value(1));
  }

  @Test
  void summaryWithEmptyPortfolioReturnsZeros() throws Exception {
    var resp =
        new PortfolioSummaryResponse(
            new BigDecimal("1000000"),
            new BigDecimal("1000000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0,
            Instant.now());
    when(portfolioService.summary()).thenReturn(resp);

    mockMvc
        .perform(get("/api/portfolio/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openPositions").value(0))
        .andExpect(jsonPath("$.totalPnl").value(0));
  }
}
