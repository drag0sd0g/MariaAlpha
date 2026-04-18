package com.mariaalpha.executionengine.model;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketState(
    String symbol,
    BigDecimal bidPrice,
    BigDecimal askPrice,
    BigDecimal lastTradePrice,
    Instant timestamp) {}
