package com.mariaalpha.strategyengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Wiring + policy knobs for the ML Signal Service gRPC client and the {@link
 * com.mariaalpha.strategyengine.ml.MlSignalGate} that consumes its output.
 *
 * <p>{@link #confidenceThreshold()} is the confidence floor below which the ML signal is treated as
 * uninformative; the strategy proceeds on its own per FR-12. Above it the gate applies the {@link
 * #vetoMode()} policy (STRICT — suppress contradictions; PERMISSIVE — log only; OFF — ML never
 * suppresses). {@link #neutralSuppress()} controls whether a high-confidence NEUTRAL prediction is
 * treated as contradicting any side (true) or as a non-opinion (false; the default and the relaxed
 * TDD §5.2.2 reading). {@link #sizingMode()} drives the quantity adjustment: NONE leaves quantity
 * untouched; SCALED multiplies it by {@code clamp(recommendedSize / sizingNeutralFraction, lower,
 * upper)} when ML agrees with high confidence.
 */
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
