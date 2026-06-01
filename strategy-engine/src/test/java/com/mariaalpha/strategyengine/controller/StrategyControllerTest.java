package com.mariaalpha.strategyengine.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.proto.signal.Direction;
import com.mariaalpha.proto.signal.MarketRegime;
import com.mariaalpha.strategyengine.ml.MlRegimeResult;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.ml.MlSignalResult;
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
  @MockitoBean private MlSignalClient mlClient;

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

  @Test
  void stateForSpecificSymbolMergesActiveSignalAndRegime() throws Exception {
    when(router.getActiveStrategyName("AAPL")).thenReturn(Optional.of("VWAP"));
    when(mlClient.getSignal("AAPL"))
        .thenReturn(Optional.of(new MlSignalResult(Direction.LONG, 0.83, 0.3)));
    when(mlClient.getRegime("AAPL"))
        .thenReturn(Optional.of(new MlRegimeResult(MarketRegime.TRENDING_UP, 0.71)));
    mockMvc
        .perform(get("/api/strategies/state").param("symbol", "AAPL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].symbol").value("AAPL"))
        .andExpect(jsonPath("$[0].activeStrategy").value("VWAP"))
        .andExpect(jsonPath("$[0].mlSignal.direction").value("LONG"))
        .andExpect(jsonPath("$[0].mlSignal.confidence").value(0.83))
        .andExpect(jsonPath("$[0].mlRegime.regime").value("TRENDING_UP"))
        .andExpect(jsonPath("$[0].mlRegime.confidence").value(0.71));
  }

  @Test
  void stateWithoutSymbolReturnsAllRoutedSymbols() throws Exception {
    when(router.routedSymbols()).thenReturn(Set.of("MSFT", "AAPL"));
    when(router.getActiveStrategyName("AAPL")).thenReturn(Optional.of("VWAP"));
    when(router.getActiveStrategyName("MSFT")).thenReturn(Optional.of("TWAP"));
    when(mlClient.getSignal(anyString())).thenReturn(Optional.empty());
    when(mlClient.getRegime(anyString())).thenReturn(Optional.empty());
    mockMvc
        .perform(get("/api/strategies/state"))
        .andExpect(status().isOk())
        // routedSymbols are sorted for stable UI ordering
        .andExpect(jsonPath("$[0].symbol").value("AAPL"))
        .andExpect(jsonPath("$[1].symbol").value("MSFT"));
  }

  @Test
  void stateHandlesMlUnavailableGracefully() throws Exception {
    when(router.getActiveStrategyName("AAPL")).thenReturn(Optional.of("VWAP"));
    when(mlClient.getSignal("AAPL")).thenReturn(Optional.empty());
    when(mlClient.getRegime("AAPL")).thenReturn(Optional.empty());
    mockMvc
        .perform(get("/api/strategies/state").param("symbol", "AAPL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].activeStrategy").value("VWAP"))
        // nullable fields omitted when empty (no JSON include)
        .andExpect(jsonPath("$[0].mlSignal").doesNotExist())
        .andExpect(jsonPath("$[0].mlRegime").doesNotExist());
  }
}
