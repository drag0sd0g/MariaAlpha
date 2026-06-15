package com.mariaalpha.strategyengine.config;

import com.mariaalpha.proto.signal.MarketRegime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strategy-engine.regime")
public record RegimeAutoSelectConfig(
    boolean enabled, double confidenceThreshold, Map<String, String> map) {

  public RegimeAutoSelectConfig {
    if (confidenceThreshold <= 0.0) {
      confidenceThreshold = 0.6;
    }
    map = (map == null) ? Map.of() : Map.copyOf(map);
  }

  public static EnumMap<MarketRegime, String> defaults() {
    var defaults = new EnumMap<MarketRegime, String>(MarketRegime.class);
    defaults.put(MarketRegime.TRENDING_UP, "MOMENTUM");
    defaults.put(MarketRegime.TRENDING_DOWN, "MOMENTUM");
    defaults.put(MarketRegime.MEAN_REVERTING, "VWAP");
    defaults.put(MarketRegime.LOW_VOLATILITY, "VWAP");
    return defaults;
  }

  public EnumMap<MarketRegime, String> resolvedMap() {
    var resolved = defaults();
    var normalized = new HashMap<String, String>();
    map.forEach((k, v) -> normalized.put(k.toUpperCase(Locale.ROOT).replace('-', '_'), v));
    for (var regime : MarketRegime.values()) {
      var override = normalized.get(regime.name());
      if (override != null && !override.isBlank()) {
        resolved.put(regime, override);
      }
    }
    return resolved;
  }
}
