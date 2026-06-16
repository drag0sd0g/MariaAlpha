package com.mariaalpha.strategyengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strategy-engine.ml")
public record MlConfig(
    String host,
    int port,
    double confidenceThreshold,
    VetoMode vetoMode,
    boolean neutralSuppress,
    SizingMode sizingMode,
    double sizingNeutralFraction,
    double sizingLowerBound,
    double sizingUpperBound) {

  public MlConfig {
    if (vetoMode == null) {
      vetoMode = VetoMode.STRICT;
    }
    if (sizingMode == null) {
      sizingMode = SizingMode.NONE;
    }
    if (sizingNeutralFraction <= 0.0) {
      sizingNeutralFraction = 0.2;
    }
    if (sizingLowerBound <= 0.0) {
      sizingLowerBound = 0.5;
    }
    if (sizingUpperBound <= 0.0) {
      sizingUpperBound = 1.5;
    }
    if (sizingLowerBound > sizingUpperBound) {
      throw new IllegalArgumentException(
          "strategy-engine.ml.sizing-lower-bound must be <= sizing-upper-bound");
    }
  }

  public enum VetoMode {
    STRICT,
    PERMISSIVE,
    OFF
  }

  public enum SizingMode {
    NONE,
    SCALED
  }
}
