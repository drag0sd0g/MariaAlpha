package com.mariaalpha.strategyengine.model;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketTick(
    String symbol,
    Instant timestamp,
    EventType eventType,
    BigDecimal price,
    long size,
    BigDecimal bidPrice,
    BigDecimal askPrice,
    long bidSize,
    long askSize,
    long cumulativeVolume,
    DataSource source,
    boolean stale) {

  public MarketTick withStale(boolean stale) {
    return new MarketTick(
        symbol,
        timestamp,
        eventType,
        price,
        size,
        bidPrice,
        askPrice,
        bidSize,
        askSize,
        cumulativeVolume,
        source,
        stale);
  }
}
