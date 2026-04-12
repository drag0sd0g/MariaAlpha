package com.mariaalpha.strategyengine.strategy.vwap;

import java.time.LocalTime;

public record TimeBin(LocalTime startTime, LocalTime endTime, double volumeFraction) {}
