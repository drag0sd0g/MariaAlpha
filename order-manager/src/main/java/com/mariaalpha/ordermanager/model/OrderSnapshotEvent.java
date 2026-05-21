package com.mariaalpha.ordermanager.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
    String venue,
    Integer displayQuantity,
    String tif,
    String parentOrderId) {

  public OrderSnapshotEvent(
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
      String venue) {
    this(
        orderId,
        clientOrderId,
        symbol,
        side,
        quantity,
        orderType,
        limitPrice,
        stopPrice,
        strategyName,
        filledQuantity,
        avgFillPrice,
        exchangeOrderId,
        venue,
        null,
        null,
        null);
  }
}
