package com.mariaalpha.strategyengine.ml;

import com.mariaalpha.proto.signal.MarketRegime;

/** Outcome of a {@code GetRegime} call (FR-16). */
public record MlRegimeResult(MarketRegime regime, double confidence) {}
