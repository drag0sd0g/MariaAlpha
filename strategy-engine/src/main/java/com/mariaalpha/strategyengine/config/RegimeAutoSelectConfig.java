package com.mariaalpha.strategyengine.config;

import com.mariaalpha.proto.signal.MarketRegime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FR-17 — regime-driven strategy selection. When {@link #enabled()} is true the strategy engine
 * routes a tick to the strategy the regime map prescribes (default: TRENDING_UP/DOWN → MOMENTUM,
 * MEAN_REVERTING/LOW_VOLATILITY → VWAP) instead of the manually-bound one in {@code
 * SymbolStrategyRouter}, provided the ML service returns a regime with confidence at least {@link
 * #confidenceThreshold()}. When confidence is below the threshold or no mapping exists for the
 * regime (e.g. HIGH_VOLATILITY by default), the engine falls back to the manual binding — making
 * this a safe, opt-in feature.
 */
@ConfigurationProperties(prefix = "strategy-engine.regime")
public record RegimeAutoSelectConfig(
    boolean enabled, double confidenceThreshold, Map<String, String> map) {

  public RegimeAutoSelectConfig {
    if (confidenceThreshold <= 0.0) {
      confidenceThreshold = 0.6;
    }
    // Defensive copy so external mutation can't reach our internal map (SpotBugs EI rule).
    map = (map == null) ? Map.of() : Map.copyOf(map);
  }

  /**
   * Default regime → strategy name mapping per TDD §3.3 FR-17. Used when a regime is not present in
   * the config-supplied {@link #map()}. HIGH_VOLATILITY intentionally omits a default so the engine
   * falls through to the manually bound strategy when the market is most uncertain.
   */
  public static EnumMap<MarketRegime, String> defaults() {
    var defaults = new EnumMap<MarketRegime, String>(MarketRegime.class);
    defaults.put(MarketRegime.TRENDING_UP, "MOMENTUM");
    defaults.put(MarketRegime.TRENDING_DOWN, "MOMENTUM");
    defaults.put(MarketRegime.MEAN_REVERTING, "VWAP");
    defaults.put(MarketRegime.LOW_VOLATILITY, "VWAP");
    return defaults;
  }

  /** Resolved (regime → strategy name) lookup, applying defaults under any unmapped regime. */
  public EnumMap<MarketRegime, String> resolvedMap() {
    var resolved = defaults();
    // Coerce keys to upper-case to match the proto enum names — config files often use kebab/snake
    // case or mixed case for readability.
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
