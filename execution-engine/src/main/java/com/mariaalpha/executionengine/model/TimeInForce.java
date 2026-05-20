package com.mariaalpha.executionengine.model;

public enum TimeInForce {
  DAY("day"),
  IOC("ioc"),
  FOK("fok"),
  GTC("gtc");

  private final String wireValue;

  TimeInForce(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static TimeInForce fromWireValue(String wireValue) {
    for (var tif : values()) {
      if (tif.wireValue.equalsIgnoreCase(wireValue)) {
        return tif;
      }
    }
    throw new IllegalArgumentException("Unknown TimeInForce wire value: " + wireValue);
  }
}
