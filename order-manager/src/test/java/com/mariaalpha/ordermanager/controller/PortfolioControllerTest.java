package com.mariaalpha.ordermanager.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.ordermanager.controller.dto.CurrencyExposureResponse;
import com.mariaalpha.ordermanager.controller.dto.PortfolioSummaryResponse;
import com.mariaalpha.ordermanager.service.CurrencyExposureService;
import com.mariaalpha.ordermanager.service.PortfolioService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PortfolioController.class)
class PortfolioControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private PortfolioService portfolioService;
  @MockitoBean private CurrencyExposureService currencyExposureService;

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

  @Test
  void currencyExposureReturnsAggregatedRows() throws Exception {
    var row =
        new CurrencyExposureResponse.Row(
            "USD",
            1,
            new BigDecimal("15000"),
            new BigDecimal("15000"),
            new BigDecimal("0"),
            new BigDecimal("250"),
            new BigDecimal("250"));
    when(currencyExposureService.exposureByCurrency())
        .thenReturn(new CurrencyExposureResponse(List.of(row), 1, Instant.now()));

    mockMvc
        .perform(get("/api/portfolio/currency-exposure"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openPositions").value(1))
        .andExpect(jsonPath("$.rows[0].currency").value("USD"))
        .andExpect(jsonPath("$.rows[0].grossExposure").value(15000))
        .andExpect(jsonPath("$.rows[0].totalPnl").value(250));
  }
}
