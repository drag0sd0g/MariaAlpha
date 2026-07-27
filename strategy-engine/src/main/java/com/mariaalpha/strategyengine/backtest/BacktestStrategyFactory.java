package com.mariaalpha.strategyengine.backtest;

import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import org.springframework.stereotype.Component;

/**
 * Produces fresh, isolated {@link TradingStrategy} instances for a backtest run. Strategies are
 * stateful singletons in the running system; a backtest must never mutate the live instance's
 * state, so it reflectively instantiates a new copy of the same class (all strategies have a no-arg
 * constructor and inject nothing) and configures it independently.
 */
@Component
public class BacktestStrategyFactory {

  private final StrategyRegistry registry;

  public BacktestStrategyFactory(StrategyRegistry registry) {
    this.registry = registry;
  }

  public TradingStrategy freshInstance(String strategyName) {
    var prototype =
        registry
            .get(strategyName)
            .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + strategyName));
    try {
      return prototype.getClass().getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Cannot instantiate strategy " + strategyName + " for backtest", e);
    }
  }
}
