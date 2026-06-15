package com.mariaalpha.executionengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
