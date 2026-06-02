package com.mariaalpha.executionengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase-2 (issue 2.7.4) Redis client settings for the cross-service position cache. Mirrors the
 * order-manager defaults so the key prefix and pub/sub channel match without manual wiring. Disable
 * via {@code execution-engine.redis.enabled=false} when running in environments that have no Redis
 * (the in-memory {@code PositionTracker} fallback continues to work).
 */
@ConfigurationProperties(prefix = "execution-engine.redis")
public record RedisConfig(
    boolean enabled, String positionKeyPrefix, String positionsPubSubChannel) {

  public RedisConfig {
    if (positionKeyPrefix == null || positionKeyPrefix.isBlank()) {
      positionKeyPrefix = "mariaalpha:position:";
    }
    if (positionsPubSubChannel == null || positionsPubSubChannel.isBlank()) {
      positionsPubSubChannel = "mariaalpha.positions.updates";
    }
  }
}
