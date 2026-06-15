package com.mariaalpha.ordermanager.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order-manager.redis")
public record RedisConfig(
    boolean enabled,
    String positionKeyPrefix,
    String positionsPubSubChannel,
    Duration positionTtl) {

  public RedisConfig {
    if (positionKeyPrefix == null || positionKeyPrefix.isBlank()) {
      positionKeyPrefix = "mariaalpha:position:";
    }
    if (positionsPubSubChannel == null || positionsPubSubChannel.isBlank()) {
      positionsPubSubChannel = "mariaalpha.positions.updates";
    }
    if (positionTtl == null || positionTtl.isZero() || positionTtl.isNegative()) {
      positionTtl = Duration.ofHours(24);
    }
  }
}
