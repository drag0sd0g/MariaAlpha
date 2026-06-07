package com.mariaalpha.executionengine.model;

import com.mariaalpha.executionengine.pegged.PegType;
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
    Instant timestamp,
    Integer displayQuantity,
    TimeInForce tif,
    String parentOrderId,
    PegType pegType,
    Integer pegOffsetBps) {

  public OrderSignal(
      String symbol,
      Side side,
      int quantity,
      OrderType orderType,
      BigDecimal limitPrice,
      BigDecimal stopPrice,
      String strategyName,
      Instant timestamp) {
    this(
        symbol,
        side,
        quantity,
        orderType,
        limitPrice,
        stopPrice,
        strategyName,
        timestamp,
        null,
        null,
        null,
        null,
        null);
  }

  public OrderSignal(
      String symbol,
      Side side,
      int quantity,
      OrderType orderType,
      BigDecimal limitPrice,
      BigDecimal stopPrice,
      String strategyName,
      Instant timestamp,
      Integer displayQuantity,
      TimeInForce tif,
      String parentOrderId) {
    this(
        symbol,
        side,
        quantity,
        orderType,
        limitPrice,
        stopPrice,
        strategyName,
        timestamp,
        displayQuantity,
        tif,
        parentOrderId,
        null,
        null);
  }
}
