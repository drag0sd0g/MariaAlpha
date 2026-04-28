package com.mariaalpha.posttrade.model;

public enum OrderStatus {
  NEW,
  SUBMITTED,
  PARTIALLY_FILLED,
  FILLED,
  CANCELLED,
  REJECTED;

  public boolean isTerminal() {
    return this == FILLED || this == CANCELLED || this == REJECTED;
  }
}
