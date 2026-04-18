package com.mariaalpha.executionengine.model;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
  NEW {
    @Override
    public Set<OrderStatus> allowedTransitions() {
      return EnumSet.of(SUBMITTED, REJECTED);
    }
  },
  SUBMITTED {
    @Override
    public Set<OrderStatus> allowedTransitions() {
      return EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED);
    }
  },
  PARTIALLY_FILLED {
    @Override
    public Set<OrderStatus> allowedTransitions() {
      return EnumSet.of(FILLED, CANCELLED);
    }
  },
  FILLED {
    @Override
    public Set<OrderStatus> allowedTransitions() {
      return EnumSet.noneOf(OrderStatus.class);
    }
  },
  CANCELLED {
    @Override
    public Set<OrderStatus> allowedTransitions() {
      return EnumSet.noneOf(OrderStatus.class);
    }
  },
  REJECTED {
    @Override
    public Set<OrderStatus> allowedTransitions() {
      return EnumSet.noneOf(OrderStatus.class);
    }
  };

  public abstract Set<OrderStatus> allowedTransitions();

  public boolean canTransitionTo(OrderStatus target) {
    return allowedTransitions().contains(target);
  }
}
