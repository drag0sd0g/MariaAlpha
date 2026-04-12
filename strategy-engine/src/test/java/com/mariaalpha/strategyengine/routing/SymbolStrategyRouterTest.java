package com.mariaalpha.strategyengine.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SymbolStrategyRouterTest {

  private SymbolStrategyRouter router;

  @BeforeEach
  void setUp() {
    var strategy = mock(TradingStrategy.class);
    when(strategy.name()).thenReturn("VWAP");
    var registry = new StrategyRegistry(List.of(strategy));
    router = new SymbolStrategyRouter(registry);
  }

  @Test
  void setActiveStrategyReturnsTrueForRegisteredStrategy() {
    assertThat(router.setActiveStrategy("AAPL", "VWAP")).isTrue();
  }

  @Test
  void setActiveStrategyReturnsFalseForUnknownStrategy() {
    assertThat(router.setActiveStrategy("AAPL", "MOMENTUM")).isFalse();
  }

  @Test
  void getActiveStrategyIsPresentAfterSet() {
    router.setActiveStrategy("AAPL", "VWAP");
    assertThat(router.getActiveStrategy("AAPL")).isPresent();
  }

  @Test
  void getActiveStrategyNameReturnsEmptyBeforeSet() {
    assertThat(router.getActiveStrategyName("AAPL")).isEmpty();
  }

  @Test
  void routedSymbolsReflectsAllSetMappings() {
    router.setActiveStrategy("AAPL", "VWAP");
    router.setActiveStrategy("MSFT", "VWAP");
    assertThat(router.routedSymbols()).containsExactlyInAnyOrder("AAPL", "MSFT");
  }
}
