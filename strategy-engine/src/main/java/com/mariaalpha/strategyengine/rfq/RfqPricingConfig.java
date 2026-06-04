package com.mariaalpha.strategyengine.rfq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the RFQ pricing engine.
 *
 * <ul>
 *   <li>{@link #baseSpreadBps()} — symmetric spread applied around the (inventory-skewed) mid;
 *       splits 50/50 into bid and ask half-spreads.
 *   <li>{@link #inventoryLambda()} — half-skew applied to the mid per unit of inventory notional,
 *       expressed as a fraction. e.g. 0.0001 with $1M of long inventory and a $1M neutral-notional
 *       shifts the mid down by 0.0001 × ($1M / $1M) = 1 bp. Positive shift down when long; up when
 *       short.
 *   <li>{@link #inventoryNeutralNotional()} — denominator for the inventory skew; the desk's "soft
 *       limit" in USD.
 *   <li>{@link #inventoryMaxSkewBps()} — cap on the inventory mid-shift so a runaway position can't
 *       push the quote arbitrarily far from market mid.
 *   <li>{@link #volScalar()} — multiplier on realised volatility (bps per look-back window) added
 *       to the half-spread on each side.
 *   <li>{@link #advScalar()} — multiplier on size/ADV (expressed in bps) added to the half-spread.
 *   <li>{@link #quoteValidityMs()} — TTL on emitted quotes; UI uses this as the count-down.
 *   <li>{@link #orderManagerBaseUrl()} — base URL used by the position lookup.
 *   <li>{@link #positionLookupTimeoutMs()} — caps the HTTP wait for an order-manager position read.
 * </ul>
 */
@ConfigurationProperties(prefix = "strategy-engine.rfq")
public record RfqPricingConfig(
    double baseSpreadBps,
    double inventoryLambda,
    double inventoryNeutralNotional,
    double inventoryMaxSkewBps,
    double volScalar,
    double advScalar,
    long quoteValidityMs,
    String orderManagerBaseUrl,
    long positionLookupTimeoutMs,
    int volatilityWindowSize) {

  public RfqPricingConfig {
    if (baseSpreadBps < 0) {
      throw new IllegalArgumentException("strategy-engine.rfq.base-spread-bps must be >= 0");
    }
    if (inventoryNeutralNotional <= 0) {
      inventoryNeutralNotional = 1_000_000.0;
    }
    if (inventoryMaxSkewBps <= 0) {
      inventoryMaxSkewBps = 50.0;
    }
    if (quoteValidityMs <= 0) {
      quoteValidityMs = 10_000;
    }
    if (positionLookupTimeoutMs <= 0) {
      positionLookupTimeoutMs = 500;
    }
    if (volatilityWindowSize <= 1) {
      volatilityWindowSize = 30;
    }
    if (orderManagerBaseUrl == null || orderManagerBaseUrl.isBlank()) {
      orderManagerBaseUrl = "http://localhost:8086";
    }
  }
}
