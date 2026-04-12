package com.mariaalpha.strategyengine.model;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSignal(
    String symbol,
    Side side,
    int quantity,
    OrderType orderType,
    BigDecimal limitPrice,
    String strategyName,
    Instant timestamp) {}
