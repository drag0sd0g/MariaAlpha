package com.mariaalpha.executionengine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.executionengine.basket.BasketStatus;
import com.mariaalpha.executionengine.basket.BasketView;
import com.mariaalpha.executionengine.service.BasketTradingService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BasketController.class)
class BasketControllerTest {

  @TestConfiguration
  static class Config {
    @Bean
    BasketTradingService basketTradingService() {
      return mock(BasketTradingService.class);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private BasketTradingService service;

  private BasketView sampleView() {
    return new BasketView(
        "b1",
        "rebalance",
        Instant.now(),
        BasketStatus.SUBMITTED,
        1,
        1,
        0,
        0,
        100,
        0,
        List.of(
            new BasketView.BasketLegView(
                "leg-1",
                "AAPL",
                com.mariaalpha.executionengine.model.Side.BUY,
                100,
                0,
                com.mariaalpha.executionengine.model.OrderStatus.SUBMITTED,
                null)));
  }

  @Test
  void submit_validBasket_returns202WithView() throws Exception {
    when(service.submit(any())).thenReturn(sampleView());

    mockMvc
        .perform(
            post("/api/execution/baskets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"rebalance\",\"legs\":[{\"symbol\":\"AAPL\",\"side\":\"BUY\","
                        + "\"orderType\":\"LIMIT\",\"quantity\":100,\"limitPrice\":150.00}]}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.basketId").value("b1"))
        .andExpect(jsonPath("$.status").value("SUBMITTED"))
        .andExpect(jsonPath("$.legs[0].legOrderId").value("leg-1"));
  }

  @Test
  void submit_emptyLegs_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/execution/baskets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"legs\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void get_knownBasket_returnsView() throws Exception {
    when(service.get("b1")).thenReturn(Optional.of(sampleView()));

    mockMvc
        .perform(get("/api/execution/baskets/{id}", "b1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.basketId").value("b1"));
  }

  @Test
  void get_unknownBasket_returns404() throws Exception {
    when(service.get("nope")).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/execution/baskets/{id}", "nope")).andExpect(status().isNotFound());
  }
}
