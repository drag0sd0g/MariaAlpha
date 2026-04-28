package com.mariaalpha.posttrade.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderSnapshotEvent(
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
    String exchangeOrderId,
    String venue) {}
