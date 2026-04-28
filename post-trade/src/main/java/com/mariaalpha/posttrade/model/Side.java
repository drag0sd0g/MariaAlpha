package com.mariaalpha.posttrade.model;

public enum Side {
  BUY,
  SELL;

  public int directionalSign() {
    return this == BUY ? 1 : -1;
  }
}
