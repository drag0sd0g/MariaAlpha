package com.mariaalpha.strategyengine.ml;

import com.mariaalpha.proto.signal.MarketRegime;

public record MlRegimeResult(MarketRegime regime, double confidence) {}
