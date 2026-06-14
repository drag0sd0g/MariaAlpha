package com.mariaalpha.executionengine.basket;

import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.Side;

/**
 * Mutable per-leg state of a basket order. One {@code BasketLeg} wraps a single child order that
 * was fanned out through the normal execution pipeline (risk → SOR → venue), tracking its target
 * quantity, cumulative fills, and terminal/working status.
 *
 * <p>All mutation happens under the owning {@link BasketState}'s monitor, so the fields need no
 * volatile/atomic treatment — {@code BasketState} is the single synchronization point (mirroring
 * how {@link com.mariaalpha.executionengine.iceberg.ParentChildOrderRegistry} centralises
 * per-parent mutation).
 */
final class BasketLeg {

  private final String legOrderId;
  private final String symbol;
  private final Side side;
  private final int targetQuantity;
  private OrderStatus status;
  private int filledQuantity;
  private String reason;

  BasketLeg(String legOrderId, String symbol, Side side, int targetQuantity) {
    this.legOrderId = legOrderId;
    this.symbol = symbol;
    this.side = side;
    this.targetQuantity = targetQuantity;
    this.status = OrderStatus.NEW;
    this.filledQuantity = 0;
  }

  void applyFill(int fillQuantity, boolean complete) {
    this.filledQuantity += fillQuantity;
    this.status = complete ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
  }

  /**
   * Record the synchronous outcome of submitting the leg. Never downgrades a leg that has already
   * started filling — a fill can race ahead of the {@code submitOrder} return on the simulated
   * adapter, and that fill is authoritative.
   */
  void applySubmissionOutcome(OrderStatus submissionStatus, String rejectionReason) {
    if (submissionStatus == OrderStatus.REJECTED) {
      this.status = OrderStatus.REJECTED;
      this.reason = rejectionReason;
    } else if (this.status == OrderStatus.NEW) {
      this.status = OrderStatus.SUBMITTED;
    }
  }

  boolean accepted() {
    return status != OrderStatus.REJECTED;
  }

  boolean fullyFilled() {
    return status == OrderStatus.FILLED;
  }

  String legOrderId() {
    return legOrderId;
  }

  int filledQuantity() {
    return filledQuantity;
  }

  BasketView.BasketLegView toView() {
    return new BasketView.BasketLegView(
        legOrderId, symbol, side, targetQuantity, filledQuantity, status, reason);
  }
}
