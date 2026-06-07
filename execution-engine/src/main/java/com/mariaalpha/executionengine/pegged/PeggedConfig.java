package com.mariaalpha.executionengine.pegged;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the pegged-order module (roadmap 3.2.3).
 *
 * <ul>
 *   <li>{@link #repegThresholdBps()} — minimum reference-price move (in bps relative to the
 *       previously-submitted child price) that triggers a cancel-and-resubmit. A threshold of 0
 *       would re-peg on every tick — chatty and expensive in venue fees, so we default to 5 bps.
 *   <li>{@link #maxOffsetBps()} — hard ceiling on |pegOffsetBps|. A misconfigured caller setting
 *       offset=10_000 would push the limit price 100% beyond the reference; the cap protects
 *       against that.
 * </ul>
 */
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
