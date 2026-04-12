package com.mariaalpha.strategyengine.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StrategyController.class)
class StrategyControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private StrategyRegistry registry;

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
}
