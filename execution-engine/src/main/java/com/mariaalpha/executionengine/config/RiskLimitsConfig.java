package com.mariaalpha.executionengine.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
    double maxAdvParticipation) {}
