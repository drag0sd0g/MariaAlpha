package com.mariaalpha.strategyengine.ml;

import com.mariaalpha.proto.signal.Direction;

public record MlSignalResult(Direction direction, double confidence) {}
