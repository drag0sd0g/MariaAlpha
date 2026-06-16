package com.mariaalpha.strategyengine.ml;

import com.mariaalpha.proto.signal.Direction;

public record MlSignalResult(Direction direction, double confidence, double recommendedSize) {

  public MlSignalResult(Direction direction, double confidence) {
    this(direction, confidence, 0.0);
  }
}
