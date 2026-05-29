package com.mariaalpha.executionengine.crossing;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Resting interest in the internal crossing book. Tracks the original `Order` plus mutable
 * `remaining` quantity so the engine can match in slices.
 *
 * <p>{@code priceAcceptable(midpoint)} encodes the limit-price gate: BUYs only cross at-or-below
 * their limit, SELLs only cross at-or-above. MARKET orders accept any non-null midpoint.
 */
public final class RestingOrder {

  private final String exchangeOrderId;
  private final Order order;
  private final Instant arrival;
  private int remaining;

  public RestingOrder(String exchangeOrderId, Order order, Instant arrival) {
    this.exchangeOrderId = exchangeOrderId;
    this.order = order;
    this.arrival = arrival;
    this.remaining = order.getRemainingQuantity();
  }

  public String exchangeOrderId() {
    return exchangeOrderId;
  }

  public Order order() {
    return order;
  }

  public String symbol() {
    return order.getSymbol();
  }

  public Side side() {
    return order.getSide();
  }

  public Instant arrival() {
    return arrival;
  }

  public int remaining() {
    return remaining;
  }

  void decrementRemaining(int qty) {
    if (qty < 0 || qty > remaining) {
      throw new IllegalArgumentException(
          "decrement out of range: qty=" + qty + " remaining=" + remaining);
    }
    remaining -= qty;
  }

  /**
   * True when {@code midpoint} is an acceptable cross price for this side. LIMIT/IOC/FOK/GTC orders
   * must straddle their limit; MARKET orders accept any non-null midpoint.
   */
  public boolean priceAcceptable(BigDecimal midpoint) {
    if (midpoint == null) {
      return false;
    }
    if (order.getOrderType() == OrderType.MARKET) {
      return true;
    }
    var limit = order.getLimitPrice();
    if (limit == null) {
      return true;
    }
    return side() == Side.BUY ? midpoint.compareTo(limit) <= 0 : midpoint.compareTo(limit) >= 0;
  }
}
