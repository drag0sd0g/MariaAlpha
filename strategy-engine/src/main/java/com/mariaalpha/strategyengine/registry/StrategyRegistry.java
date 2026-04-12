package com.mariaalpha.strategyengine.registry;

import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StrategyRegistry {
  private static final Logger LOG = LoggerFactory.getLogger(StrategyRegistry.class);

  private final Map<String, TradingStrategy> strategies = new ConcurrentHashMap<>();

  public StrategyRegistry(List<TradingStrategy> discoveredStrategies) {
    discoveredStrategies.forEach(this::register);
    LOG.info(
        "StrategyRegistry initialized with {} strategies {}",
        strategies.size(),
        strategies.keySet());
  }

  public void register(TradingStrategy strategy) {
    strategies.put(strategy.name(), strategy);
  }

  public Optional<TradingStrategy> get(String name) {
    return Optional.ofNullable(strategies.get(name));
  }

  public Set<String> availableStrategies() {
    return Set.copyOf(strategies.keySet());
  }
}
