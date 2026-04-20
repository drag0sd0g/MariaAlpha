package com.mariaalpha.ordermanager.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
