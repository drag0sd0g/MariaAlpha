package com.mariaalpha.executionengine.router;

public record Venue(
    String name,
    VenueType type,
    long avgLatencyMs,
    int takerFeeBps,
    int makerRebateBps,
    double leakageScore,
    int topOfBookSize,
    double fillRate,
    String adapterBean,
    boolean enabled) {}
