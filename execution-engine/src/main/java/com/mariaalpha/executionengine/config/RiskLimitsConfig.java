package com.mariaalpha.executionengine.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Pre-trade risk-check thresholds.
 *
 * <p>The core knobs are {@code maxOrderNotional}, {@code maxPositionPerSymbol}, {@code
 * maxPortfolioExposure}, {@code maxOpenOrders}, and {@code maxDailyLoss}. The sector / beta / ADV
 * risk checks add:
 *
 * <ul>
 *   <li>{@code sectorExposureLimits} — per-sector $-cap; the {@code defaultSectorExposureLimit}
 *       applies to any sector not explicitly listed (and to the synthetic {@code UNKNOWN} sector
 *       used for unmapped symbols).
 *   <li>{@code maxAbsoluteBetaWeightedExposure} — cap on |Σ position_notional × beta| in $; a
 *       portfolio that is long $1M of NVDA (β≈1.6) carries $1.6M of beta-weighted exposure.
 *   <li>{@code maxAdvParticipation} — fraction of a symbol's Average Daily Volume that a single
 *       order may consume (e.g. {@code 0.10} ≈ 10% of ADV). Orders exceeding this are rejected
 *       up-front because they would create unmanageable market impact.
 *   <li>{@code maxIntradayVar} — cap on the portfolio's parametric daily VaR in $ at the configured
 *       {@code varConfidenceLevel}. Computed per-position as |notional| × σ_ann / √trading-days ×
 *       z(confidence), summed in absolute value (no diversification credit — the conservative
 *       reading). Set to {@code 0} to disable. See {@link
 *       com.mariaalpha.executionengine.risk.IntradayVarCheck}.
 *   <li>{@code correlatedClusters} — named lists of symbols that historically co-move; each cluster
 *       gets a $-cap on its gross exposure. See {@link
 *       com.mariaalpha.executionengine.risk.CorrelatedPositionsCheck}.
 * </ul>
 */
@ConfigurationProperties(prefix = "execution-engine.risk")
public record RiskLimitsConfig(
    long maxOrderNotional,
    long maxPositionPerSymbol,
    long maxPortfolioExposure,
    int maxOpenOrders,
    long maxDailyLoss,
    Map<String, Long> sectorExposureLimits,
    long defaultSectorExposureLimit,
    long maxAbsoluteBetaWeightedExposure,
    double maxAdvParticipation,
    long maxIntradayVar,
    double varConfidenceLevel,
    double varTradingDaysPerYear,
    List<CorrelatedCluster> correlatedClusters) {

  /**
   * Spring Boot's {@code @ConfigurationProperties} binder needs to pick one constructor when a
   * record has multiple. Without this annotation, binding falls back to Java-bean mode, which
   * requires a no-arg constructor that records can't have — the bean factory then fails with {@code
   * NoSuchMethodException: <init>()} and the whole context fails to start. Pinning the canonical
   * constructor here is the documented Boot-3 fix.
   */
  @ConstructorBinding
  public RiskLimitsConfig {}

  /**
   * Legacy constructor for call sites that predate the VaR (3.5.1) and correlated-positions (3.5.2)
   * fields. Defaults all four new knobs to a self-disabling value (zero limits, empty cluster list)
   * — existing tests stay green without touching them.
   */
  public RiskLimitsConfig(
      long maxOrderNotional,
      long maxPositionPerSymbol,
      long maxPortfolioExposure,
      int maxOpenOrders,
      long maxDailyLoss,
      Map<String, Long> sectorExposureLimits,
      long defaultSectorExposureLimit,
      long maxAbsoluteBetaWeightedExposure,
      double maxAdvParticipation) {
    this(
        maxOrderNotional,
        maxPositionPerSymbol,
        maxPortfolioExposure,
        maxOpenOrders,
        maxDailyLoss,
        sectorExposureLimits,
        defaultSectorExposureLimit,
        maxAbsoluteBetaWeightedExposure,
        maxAdvParticipation,
        0L,
        0.95,
        252.0,
        List.of());
  }

  /**
   * A named cluster of symbols whose gross dollar exposure must not exceed {@code limit}. Used by
   * {@link com.mariaalpha.executionengine.risk.CorrelatedPositionsCheck} to enforce concentration
   * limits across symbols whose returns historically move together (e.g. a "MEGACAP_TECH" basket).
   * The same symbol can appear in multiple clusters — each cluster is evaluated independently.
   */
  public record CorrelatedCluster(String name, List<String> symbols, long limit) {}
}
