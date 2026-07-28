package com.mariaalpha.strategyengine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.strategyengine.backtest.BacktestEngine;
import com.mariaalpha.strategyengine.backtest.BacktestMetrics;
import com.mariaalpha.strategyengine.backtest.BacktestProperties;
import com.mariaalpha.strategyengine.backtest.BacktestResult;
import com.mariaalpha.strategyengine.backtest.report.HtmlReportWriter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BacktestControllerTest {

  private BacktestEngine engine;
  private MockMvc mvc;

  @BeforeEach
  void setUp(@TempDir Path dir) {
    engine = mock(BacktestEngine.class);
    var reportWriter =
        new HtmlReportWriter(new BacktestProperties(null, 0, 1.0, 2.0, dir.toString()));
    var controller = new BacktestController(engine, reportWriter);
    var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(
                new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter(mapper))
            .build();
  }

  private static BacktestResult result() {
    var metrics =
        new BacktestMetrics(
            2.5,
            1.0,
            1.0,
            0.5,
            2,
            4,
            0.3,
            BigDecimal.valueOf(100_000),
            BigDecimal.valueOf(102_500),
            BigDecimal.valueOf(2_500),
            BigDecimal.ZERO);
    return new BacktestResult(
        "MOMENTUM",
        List.of("AAPL"),
        Instant.parse("2026-03-24T14:30:00Z"),
        Instant.parse("2026-03-24T15:30:00Z"),
        60,
        240,
        false,
        "note",
        metrics,
        List.of(),
        List.of(),
        null);
  }

  @Test
  void postRunsBacktestAndReturnsMetrics() throws Exception {
    when(engine.run(any())).thenReturn(result());

    mvc.perform(
            post("/api/backtest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategyName\":\"MOMENTUM\",\"symbols\":[\"AAPL\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategyName").value("MOMENTUM"))
        .andExpect(jsonPath("$.metrics.totalReturnPct").value(2.5))
        .andExpect(jsonPath("$.reportPath").isNotEmpty());
  }

  @Test
  void invalidRequestReturnsBadRequest() throws Exception {
    when(engine.run(any())).thenThrow(new IllegalArgumentException("strategyName is required"));

    mvc.perform(
            post("/api/backtest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbols\":[\"AAPL\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reportIsNotFoundUntilABacktestHasRun() throws Exception {
    mvc.perform(get("/api/backtest/report")).andExpect(status().isNotFound());
  }

  @Test
  void reportIsServedAsHtmlAfterARun() throws Exception {
    when(engine.run(any())).thenReturn(result());
    mvc.perform(
            post("/api/backtest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategyName\":\"MOMENTUM\",\"symbols\":[\"AAPL\"]}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/backtest/report"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("<!doctype html>")));
  }
}
