package com.mariaalpha.strategyengine.routing;

import com.mariaalpha.proto.signal.MarketRegime;
import com.mariaalpha.strategyengine.config.RegimeAutoSelectConfig;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.util.EnumMap;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RegimeBasedStrategySelector {

  private static final Logger LOG = LoggerFactory.getLogger(RegimeBasedStrategySelector.class);

  private final RegimeAutoSelectConfig config;
  private final MlSignalClient mlClient;
  private final StrategyRegistry registry;
  private final EnumMap<MarketRegime, String> regimeMap;

  public RegimeBasedStrategySelector(
      RegimeAutoSelectConfig config, MlSignalClient mlClient, StrategyRegistry registry) {
    this.config = config;
    this.mlClient = mlClient;
    this.registry = registry;
    this.regimeMap = config.resolvedMap();
    LOG.info(
        "RegimeBasedStrategySelector enabled={} threshold={} map={}",
        config.enabled(),
        config.confidenceThreshold(),
        regimeMap);
  }

  public Optional<TradingStrategy> selectFor(String symbol) {
    if (!config.enabled()) {
      return Optional.empty();
    }
    var regimeResult = mlClient.getRegime(symbol);
    if (regimeResult.isEmpty()) {
      return Optional.empty();
    }
    var result = regimeResult.get();
    if (result.confidence() < config.confidenceThreshold()) {
      return Optional.empty();
    }
    var strategyName = regimeMap.get(result.regime());
    if (strategyName == null) {
      return Optional.empty();
    }
    var strategy = registry.get(strategyName);
    if (strategy.isEmpty()) {
      LOG.warn(
          "RegimeBasedStrategySelector: regime {} mapped to '{}' but strategy not registered",
          result.regime(),
          strategyName);
    }
    return strategy;
  }
}
