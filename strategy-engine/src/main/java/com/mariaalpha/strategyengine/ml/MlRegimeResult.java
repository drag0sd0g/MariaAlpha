package com.mariaalpha.strategyengine.ml;

import com.mariaalpha.proto.signal.MarketRegime;

/** Outcome of a {@code GetRegime} call (FR-16, issue 2.3.1). */
public record MlRegimeResult(MarketRegime regime, double confidence) {}
