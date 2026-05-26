package com.mariaalpha.strategyengine.strategy.twap;

import java.time.LocalTime;

/**
 * One equal-duration time interval of a TWAP schedule, half-open {@code [startTime, endTime)} in
 * the market time zone. Unlike VWAP's {@code TimeBin}, a TWAP slice carries no volume fraction —
 * the parent quantity is divided equally across the configured number of slices.
 */
public record TwapSlice(LocalTime startTime, LocalTime endTime) {}
