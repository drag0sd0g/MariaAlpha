package com.mariaalpha.strategyengine.strategy.shortfall;

import java.time.LocalTime;

public record ShortfallSlice(LocalTime startTime, LocalTime endTime) {}
