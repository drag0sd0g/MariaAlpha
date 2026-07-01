package com.mariaalpha.posttrade.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "post-trade.tca")
public record TcaConfig(
    long marketDataCacheTtlSeconds,
    int marketDataCacheMaxTicksPerSymbol,
    long arrivalSnapshotMaxLookbackSeconds,
    String orderManagerBaseUrl,
    long orderManagerHttpTimeoutMs,
    int orderManagerFetchMaxAttempts,
    long orderManagerFetchRetryDelayMs) {

  public Duration cacheTtl() {
    return Duration.ofSeconds(marketDataCacheTtlSeconds);
  }

  public Duration arrivalLookback() {
    return Duration.ofSeconds(arrivalSnapshotMaxLookbackSeconds);
  }

  public Duration httpTimeout() {
    return Duration.ofMillis(orderManagerHttpTimeoutMs);
  }

  public int fetchMaxAttempts() {
    return Math.max(1, orderManagerFetchMaxAttempts);
  }

  public Duration fetchRetryDelay() {
    return Duration.ofMillis(orderManagerFetchRetryDelayMs);
  }
}
