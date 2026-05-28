package com.mariaalpha.strategyengine.strategy.close;

import java.time.LocalTime;

/**
 * One equal-duration time interval of a Close strategy's <em>pre-close</em> working window,
 * half-open {@code [startTime, endTime)} in the market time zone.
 *
 * <p>Identical in shape to TWAP's slice — the pre-close window {@code [windowStart, mocCutoff)} is
 * divided into evenly spaced intervals. What differs is that only a configurable {@code
 * preCloseFraction} of the parent quantity is distributed across these slices; the remainder is
 * reserved for the Market-on-Close (MOC) child that fires once at {@code mocCutoff}.
 */
public record CloseSlice(LocalTime startTime, LocalTime endTime) {}
