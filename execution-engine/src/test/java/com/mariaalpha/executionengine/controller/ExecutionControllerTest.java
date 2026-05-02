package com.mariaalpha.executionengine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.executionengine.controller.dto.SubmitOrderRequest;
import com.mariaalpha.executionengine.controller.dto.SubmitOrderResponse;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.risk.DailyLossMonitor;
import com.mariaalpha.executionengine.service.ManualOrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
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

    @Bean
    ManualOrderService manualOrderService() {
      return mock(ManualOrderService.class);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private DailyLossMonitor monitor;
  @Autowired private ManualOrderService manualOrderService;

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

  @Test
  void submitOrder_validRequest_returns202WithOrderId() throws Exception {
    var orderId = UUID.randomUUID().toString();
    when(manualOrderService.submit(any(SubmitOrderRequest.class)))
        .thenReturn(new SubmitOrderResponse(orderId, OrderStatus.SUBMITTED, Instant.now()));

    mockMvc
        .perform(
            post("/api/execution/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "symbol": "AAPL",
                      "side": "BUY",
                      "orderType": "LIMIT",
                      "quantity": 100,
                      "limitPrice": 178.50
                    }
                    """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.orderId").value(orderId))
        .andExpect(jsonPath("$.status").value("SUBMITTED"));
  }

  @Test
  void submitOrder_missingSymbol_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/execution/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "side": "BUY",
                      "orderType": "LIMIT",
                      "quantity": 100,
                      "limitPrice": 178.50
                    }
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cancel_existingOrder_returns204() throws Exception {
    var orderId = UUID.randomUUID().toString();
    when(manualOrderService.cancel(orderId)).thenReturn(true);

    mockMvc.perform(delete("/api/execution/orders/" + orderId)).andExpect(status().isNoContent());
  }

  @Test
  void cancel_unknownOrder_returns404() throws Exception {
    var orderId = UUID.randomUUID().toString();
    when(manualOrderService.cancel(orderId)).thenReturn(false);

    mockMvc.perform(delete("/api/execution/orders/" + orderId)).andExpect(status().isNotFound());
  }
}
