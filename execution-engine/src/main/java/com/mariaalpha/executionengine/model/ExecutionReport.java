package com.mariaalpha.executionengine.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutionReport(
    String exchangeOrderId,
    BigDecimal fillPrice,
    int fillQuantity,
    int remainingQuantity,
    String venue,
    Instant timestamp,
    String reason) {

  public ExecutionReport(
      String exchangeOrderId,
      BigDecimal fillPrice,
      int fillQuantity,
      int remainingQuantity,
      String venue,
      Instant timestamp) {
    this(exchangeOrderId, fillPrice, fillQuantity, remainingQuantity, venue, timestamp, null);
  }
}
