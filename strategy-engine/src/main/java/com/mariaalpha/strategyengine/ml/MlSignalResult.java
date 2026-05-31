package com.mariaalpha.strategyengine.ml;

import com.mariaalpha.proto.signal.Direction;

/**
 * Outcome of a {@code GetSignal} call. {@code recommendedSize} is the ML model's recommended
 * position size as a fraction of available capital, used by the {@link MlSignalGate} to optionally
 * scale the strategy's order quantity when the ML side agrees with high confidence.
 */
public record MlSignalResult(Direction direction, double confidence, double recommendedSize) {

  public MlSignalResult(Direction direction, double confidence) {
    this(direction, confidence, 0.0);
  }
}
