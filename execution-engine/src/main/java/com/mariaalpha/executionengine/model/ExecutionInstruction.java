package com.mariaalpha.executionengine.model;

import java.math.BigDecimal;

public record ExecutionInstruction(
    Order order, TimeInForce timeInForce, BigDecimal adjustedLimitPrice, Integer displayQuantity) {
  public ExecutionInstruction(Order order, TimeInForce tif, BigDecimal limitPrice) {
    this(order, tif, limitPrice, null);
  }
}
