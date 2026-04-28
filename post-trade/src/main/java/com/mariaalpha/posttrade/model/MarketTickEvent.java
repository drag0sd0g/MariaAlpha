package com.mariaalpha.posttrade.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketTickEvent(
    String symbol,
    Instant timestamp,
    EventType eventType,
    BigDecimal price,
    Long size,
    BigDecimal bidPrice,
    BigDecimal askPrice,
    Long bidSize,
    Long askSize,
    Long cumulativeVolume,
    DataSource source,
    boolean stale) {}
