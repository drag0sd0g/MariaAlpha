package com.mariaalpha.strategyengine.strategy.shortfall;

import java.time.LocalTime;

/**
 * One equal-duration time interval of an Implementation Shortfall schedule, half-open {@code
 * [startTime, endTime)} in the market time zone.
 *
 * <p>Identical in shape to TWAP's {@code TwapSlice} — the intervals are evenly spaced. What differs
 * is the per-slice <em>allocation</em>: TWAP splits the parent equally across slices, whereas
 * Implementation Shortfall front-loads it via an Almgren–Chriss optimal trajectory (see {@link
 * ImplementationShortfallStrategy}). The slice itself carries no weight; the strategy holds the
 * computed share allocations alongside this schedule.
 */
public record ShortfallSlice(LocalTime startTime, LocalTime endTime) {}
