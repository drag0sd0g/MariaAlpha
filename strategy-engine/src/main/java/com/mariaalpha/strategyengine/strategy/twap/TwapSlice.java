package com.mariaalpha.strategyengine.strategy.twap;

import java.time.LocalTime;

public record TwapSlice(LocalTime startTime, LocalTime endTime) {}
