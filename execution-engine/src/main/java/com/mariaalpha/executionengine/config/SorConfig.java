package com.mariaalpha.executionengine.config;

import com.mariaalpha.executionengine.router.Venue;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution-engine.sor")
public record SorConfig(
    String mode,
    long maxLatencyMs,
    int maxFeeBps,
    int maxPriceImprovementBps,
    int decisionCacheSize,
    Weights weights,
    List<Venue> venues) {

  public record Weights(
      double priceImprovement,
      double liquidity,
      double latency,
      double fees,
      double informationLeakage) {
    public Map<String, Double> asMap() {
      return Map.of(
          "PriceImprovement", priceImprovement,
          "Liquidity", liquidity,
          "Latency", latency,
          "Fees", fees,
          "InformationLeakage", informationLeakage);
    }

    public double sum() {
      return priceImprovement + liquidity + latency + fees + informationLeakage;
    }
  }
}
