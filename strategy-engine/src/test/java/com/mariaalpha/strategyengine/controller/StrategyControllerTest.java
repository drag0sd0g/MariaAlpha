package com.mariaalpha.strategyengine.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StrategyController.class)
class StrategyControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private StrategyRegistry registry;
  @MockitoBean private SymbolStrategyRouter router;

  @Test
  void listStrategiesReturnsAvailableNames() throws Exception {
    when(registry.availableStrategies()).thenReturn(Set.of("VWAP", "TWAP"));
    mockMvc
        .perform(get("/api/strategies"))
        .andExpect(status().isOk())
        .andExpect(content().json("[\"VWAP\",\"TWAP\"]", false));
  }

  @Test
  void listStrategiesReturnsEmptyWhenNoneRegistered() throws Exception {
    when(registry.availableStrategies()).thenReturn(Set.of());
    mockMvc
        .perform(get("/api/strategies"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void setActiveStrategyReturnsOkWhenStrategyKnown() throws Exception {
    when(router.setActiveStrategy("AAPL", "VWAP")).thenReturn(true);
    mockMvc
        .perform(
            put("/api/strategies/AAPL")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategyName\":\"VWAP\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void setActiveStrategyReturnsBadRequestWhenStrategyUnknown() throws Exception {
    when(router.setActiveStrategy(anyString(), eq("MOMENTUM"))).thenReturn(false);
    mockMvc
        .perform(
            put("/api/strategies/AAPL")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategyName\":\"MOMENTUM\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getActiveStrategyReturnsNameWhenBound() throws Exception {
    when(router.getActiveStrategyName("AAPL")).thenReturn(Optional.of("VWAP"));
    mockMvc
        .perform(get("/api/strategies/AAPL/active"))
        .andExpect(status().isOk())
        .andExpect(content().string("VWAP"));
  }

  @Test
  void getActiveStrategyReturnsNotFoundWhenNoBound() throws Exception {
    when(router.getActiveStrategyName("AAPL")).thenReturn(Optional.empty());
    mockMvc.perform(get("/api/strategies/AAPL/active")).andExpect(status().isNotFound());
  }

  @Test
  void getParametersReturnsMapWhenStrategyExists() throws Exception {
    var strategy = mock(TradingStrategy.class);
    when(strategy.getParameters()).thenReturn(Map.of("targetQuantity", 1000));
    when(registry.get("VWAP")).thenReturn(Optional.of(strategy));
    mockMvc
        .perform(get("/api/strategies/VWAP/parameters"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"targetQuantity\":1000}"));
  }

  @Test
  void getParametersReturnsNotFoundWhenStrategyUnknown() throws Exception {
    when(registry.get("UNKNOWN")).thenReturn(Optional.empty());
    mockMvc.perform(get("/api/strategies/UNKNOWN/parameters")).andExpect(status().isNotFound());
  }
}
