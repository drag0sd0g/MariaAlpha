package com.mariaalpha.executionengine.basket;

import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.Side;
import java.time.Instant;
import java.util.List;

public record BasketView(
    String basketId,
    String name,
    Instant createdAt,
    BasketStatus status,
    int totalLegs,
    int acceptedLegs,
    int rejectedLegs,
    int filledLegs,
    int targetQuantity,
    int filledQuantity,
    List<BasketLegView> legs) {

  public record BasketLegView(
      String legOrderId,
      String symbol,
      Side side,
      int targetQuantity,
      int filledQuantity,
      OrderStatus status,
      String reason) {}
}
