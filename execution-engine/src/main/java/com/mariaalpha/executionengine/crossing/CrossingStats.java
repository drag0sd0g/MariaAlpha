package com.mariaalpha.executionengine.crossing;

import java.math.BigDecimal;

public record CrossingStats(
    long crossesTotal,
    long internalCrossesTotal,
    long syntheticCrossesTotal,
    long sharesCrossedTotal,
    BigDecimal spreadCapturedNotional,
    int restingOrdersBuy,
    int restingOrdersSell,
    int symbols) {}
