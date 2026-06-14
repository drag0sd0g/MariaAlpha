package com.mariaalpha.executionengine.basket;

import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.Side;
import java.time.Instant;
import java.util.List;

/**
 * Immutable, serialisable snapshot of a basket order and its legs — the read model returned by the
 * REST surface. Built under the {@link BasketState} monitor so the leg list and aggregates are
 * internally consistent.
 */
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

  /** Per-leg projection within a {@link BasketView}. */
  public record BasketLegView(
      String legOrderId,
      String symbol,
      Side side,
      int targetQuantity,
      int filledQuantity,
      OrderStatus status,
      String reason) {}
}
