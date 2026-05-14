package com.mariaalpha.executionengine.controller.dto;

import com.mariaalpha.executionengine.router.Venue;
import com.mariaalpha.executionengine.router.VenueType;

public record VenueResponse(
    String name,
    VenueType type,
    long avgLatencyMs,
    int takerFeeBps,
    int makerRebateBps,
    double leakageScore,
    int topOfBookSize,
    double fillRate,
    boolean enabled) {
  public static VenueResponse from(Venue v) {
    return new VenueResponse(
        v.name(),
        v.type(),
        v.avgLatencyMs(),
        v.takerFeeBps(),
        v.makerRebateBps(),
        v.leakageScore(),
        v.topOfBookSize(),
        v.fillRate(),
        v.enabled());
  }
}
