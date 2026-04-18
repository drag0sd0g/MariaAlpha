package com.mariaalpha.executionengine.model;

public enum OrderType {
  MARKET("market"),
  LIMIT("limit"),
  STOP("stop");

  private final String name;

  OrderType(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
