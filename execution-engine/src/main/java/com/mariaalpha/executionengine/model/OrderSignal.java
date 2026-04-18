package com.mariaalpha.executionengine.model;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSignal(
    String symbol,
    Side side,
    int quantity,
    OrderType orderType,
    BigDecimal limitPrice,
    BigDecimal stopPrice,
    String strategyName,
    Instant timestamp) {}
