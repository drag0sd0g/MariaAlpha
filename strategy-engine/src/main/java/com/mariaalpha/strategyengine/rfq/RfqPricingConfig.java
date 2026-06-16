package com.mariaalpha.strategyengine.rfq;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
