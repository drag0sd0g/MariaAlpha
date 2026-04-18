package com.mariaalpha.executionengine.lifecycle;

import com.mariaalpha.executionengine.model.OrderStatus;

public class IllegalStateTransitionException extends RuntimeException {

  public IllegalStateTransitionException(String orderId, OrderStatus from, OrderStatus to) {
    super(String.format("Order %s: invalid transition %s → %s", orderId, from, to));
  }
}
