package com.mariaalpha.executionengine.model;

import java.math.BigDecimal;

public record OrderSnapshot(
    String orderId,
    String clientOrderId,
    String symbol,
    Side side,
    int quantity,
    OrderType orderType,
    BigDecimal limitPrice,
    BigDecimal stopPrice,
    String strategyName,
    int filledQuantity,
    BigDecimal avgFillPrice,
    String exchangeOrderId) {}
