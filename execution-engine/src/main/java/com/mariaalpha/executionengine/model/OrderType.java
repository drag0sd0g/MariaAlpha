package com.mariaalpha.executionengine.model;

public enum OrderType {
  MARKET("market"),
  LIMIT("limit"),
  STOP("stop"),
  IOC("ioc"),
  FOK("fok"),
  GTC("gtc"),
  ICEBERG("iceberg"),
  PEGGED("pegged");

  private final String name;

  OrderType(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
