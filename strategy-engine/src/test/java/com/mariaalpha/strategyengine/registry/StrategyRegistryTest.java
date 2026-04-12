package com.mariaalpha.strategyengine.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyRegistryTest {

  @Test
  void registersSpringDiscoveredStrategies() {
    var s1 = mockStrategy("VWAP");
    var s2 = mockStrategy("TWAP");
    var registry = new StrategyRegistry(List.of(s1, s2));
    assertThat(registry.availableStrategies()).containsExactlyInAnyOrder("VWAP", "TWAP");
  }

  @Test
  void getReturnsRegisteredStrategy() {
    var strategy = mockStrategy("VWAP");
    var registry = new StrategyRegistry(List.of(strategy));
    assertThat(registry.get("VWAP")).contains(strategy);
  }

  @Test
  void getReturnsEmptyForUnknown() {
    var registry = new StrategyRegistry(List.of());
    assertThat(registry.get("NONEXISTENT")).isEmpty();
  }

  @Test
  void manualRegisterAddsStrategy() {
    var registry = new StrategyRegistry(List.of());
    var strategy = mockStrategy("MOMENTUM");
    registry.register(strategy);
    assertThat(registry.availableStrategies()).contains("MOMENTUM");
    assertThat(registry.get("MOMENTUM")).contains(strategy);
  }

  @Test
  void availableStrategiesReturnsImmutableCopy() {
    var registry = new StrategyRegistry(List.of(mockStrategy("VWAP")));
    var strategies = registry.availableStrategies();
    assertThrows(UnsupportedOperationException.class, () -> strategies.add("HACK"));
  }

  @Test
  void duplicateNameOverwritesPrevious() {
    var first = mockStrategy("VWAP");
    var second = mockStrategy("VWAP");
    var registry = new StrategyRegistry(List.of(first));
    registry.register(second);
    assertThat(registry.get("VWAP")).contains(second);
  }

  private static TradingStrategy mockStrategy(String name) {
    var strategy = mock(TradingStrategy.class);
    when(strategy.name()).thenReturn(name);
    return strategy;
  }
}
