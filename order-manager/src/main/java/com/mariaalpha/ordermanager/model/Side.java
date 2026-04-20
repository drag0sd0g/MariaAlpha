package com.mariaalpha.ordermanager.model;

public enum Side {
  BUY,
  SELL;

  public int signum() {
    return this == BUY ? 1 : -1;
  }

  public Side opposite() {
    return this == BUY ? SELL : BUY;
  }
}
