package com.mariaalpha.executionengine.crossing;

import com.mariaalpha.executionengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;

public record MidpointCross(
    String aggressorExchangeOrderId,
    String counterpartyExchangeOrderId,
    String symbol,
    Side side,
    int quantity,
    BigDecimal midpoint,
    BigDecimal spreadBps,
    boolean synthetic,
    Instant timestamp) {}
