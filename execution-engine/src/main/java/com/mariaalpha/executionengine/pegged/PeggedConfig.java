package com.mariaalpha.executionengine.pegged;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution-engine.pegged")
public record PeggedConfig(int repegThresholdBps, int maxOffsetBps) {

  public PeggedConfig {
    if (repegThresholdBps < 0) {
      repegThresholdBps = 5;
    }
    if (maxOffsetBps <= 0) {
      maxOffsetBps = 100;
    }
  }
}
