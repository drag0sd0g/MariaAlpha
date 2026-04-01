package com.mariaalpha.marketdatagateway.model;

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
    DataSource source) {}
