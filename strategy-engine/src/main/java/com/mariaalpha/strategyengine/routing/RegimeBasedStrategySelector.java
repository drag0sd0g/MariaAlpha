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

/**
 * FR-17 implementation: maps the current ML regime classification for a symbol to a strategy. The
 * selector is consulted by {@code StrategyEvaluationService} before falling back to the manually
 * bound strategy in {@link SymbolStrategyRouter}, and only returns a strategy when:
 *
 * <ol>
 *   <li>auto-select is enabled in config,
 *   <li>the ML service returns a regime (i.e. the gRPC call succeeded and the circuit breaker is
 *       closed),
 *   <li>the regime confidence meets the configured threshold,
 *   <li>the regime has a configured mapping (HIGH_VOLATILITY and UNKNOWN are unmapped by default),
 *   <li>and that mapped strategy is registered in the {@link StrategyRegistry}.
 * </ol>
 *
 * Any other case returns {@link Optional#empty()} so the caller falls through to the user's manual
 * binding.
 */
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

  /** Returns the strategy the regime model recommends for {@code symbol}, or empty. */
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
