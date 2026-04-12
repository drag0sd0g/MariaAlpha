package com.mariaalpha.strategyengine.routing;

import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SymbolStrategyRouter {

  private static final Logger LOG = LoggerFactory.getLogger(SymbolStrategyRouter.class);

  private final StrategyRegistry registry;
  private final ConcurrentHashMap<String, String> symbolToStrategy = new ConcurrentHashMap<>();

  public SymbolStrategyRouter(StrategyRegistry registry) {
    this.registry = registry;
  }

  /**
   * Binds {@code symbol} to {@code strategyName}. Returns {@code true} if the strategy is
   * registered, {@code false} if the name is unknown (mapping is not changed in that case).
   */
  public boolean setActiveStrategy(String symbol, String strategyName) {
    if (registry.get(strategyName).isEmpty()) {
      LOG.warn("Cannot route {} → {}: strategy not found", symbol, strategyName);
      return false;
    }
    symbolToStrategy.put(symbol, strategyName);
    LOG.info("Routing {} → {}", symbol, strategyName);
    return true;
  }

  /** Returns the strategy name currently bound to {@code symbol}, or empty if none. */
  public Optional<String> getActiveStrategyName(String symbol) {
    return Optional.ofNullable(symbolToStrategy.get(symbol));
  }

  /** Returns the live {@link TradingStrategy} bound to {@code symbol}, or empty if none. */
  public Optional<TradingStrategy> getActiveStrategy(String symbol) {
    return getActiveStrategyName(symbol).flatMap(registry::get);
  }

  /** Returns an immutable snapshot of all currently routed symbols. */
  public Set<String> routedSymbols() {
    return Set.copyOf(symbolToStrategy.keySet());
  }
}
