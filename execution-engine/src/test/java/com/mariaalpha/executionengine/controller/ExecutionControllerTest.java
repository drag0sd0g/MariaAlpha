package com.mariaalpha.executionengine.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.executionengine.risk.DailyLossMonitor;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExecutionController.class)
class ExecutionControllerTest {

  @TestConfiguration
  static class Config {
    @Bean
    DailyLossMonitor dailyLossMonitor() {
      var monitor = mock(DailyLossMonitor.class);
      when(monitor.getDailyPnl()).thenReturn(new BigDecimal("-5000"));
      when(monitor.isTradingHalted()).thenReturn(false);
      return monitor;
    }
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private DailyLossMonitor monitor;

  @Test
  void resumeReturnsOk() throws Exception {
    mockMvc
        .perform(post("/api/execution/resume"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("resumed"));
    verify(monitor).resume();
  }

  @Test
  void statusShowsHaltState() throws Exception {
    mockMvc
        .perform(get("/api/execution/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tradingHalted").value(false))
        .andExpect(jsonPath("$.dailyPnl").value("-5000"));
  }
}
