package com.mariaalpha.ordermanager.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum OrderStatus {
  NEW,
  SUBMITTED,
  PARTIALLY_FILLED,
  FILLED,
  CANCELLED,
  REJECTED;

  private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS =
      Map.of(
          NEW, EnumSet.of(SUBMITTED, REJECTED, CANCELLED),
          SUBMITTED, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED),
          PARTIALLY_FILLED, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED),
          FILLED, EnumSet.noneOf(OrderStatus.class),
          CANCELLED, EnumSet.noneOf(OrderStatus.class),
          REJECTED, EnumSet.noneOf(OrderStatus.class));

  public boolean canTransitionTo(OrderStatus next) {
    return TRANSITIONS.get(this).contains(next);
  }

  public boolean isTerminal() {
    return this == FILLED || this == CANCELLED || this == REJECTED;
  }
}
