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

  public boolean setActiveStrategy(String symbol, String strategyName) {
    if (registry.get(strategyName).isEmpty()) {
      LOG.warn("Cannot route {} → {}: strategy not found", symbol, strategyName);
      return false;
    }
    symbolToStrategy.put(symbol, strategyName);
    LOG.info("Routing {} → {}", symbol, strategyName);
    return true;
  }

  public Optional<String> getActiveStrategyName(String symbol) {
    return Optional.ofNullable(symbolToStrategy.get(symbol));
  }

  public Optional<TradingStrategy> getActiveStrategy(String symbol) {
    return getActiveStrategyName(symbol).flatMap(registry::get);
  }

  public Set<String> routedSymbols() {
    return Set.copyOf(symbolToStrategy.keySet());
  }

  public boolean clearActiveStrategy(String symbol) {
    var previous = symbolToStrategy.remove(symbol);
    if (previous != null) {
      LOG.info("Unrouted {} (was → {})", symbol, previous);
      return true;
    }
    return false;
  }
}
