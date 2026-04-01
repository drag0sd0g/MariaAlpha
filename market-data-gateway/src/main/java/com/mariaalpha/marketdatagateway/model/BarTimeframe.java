package com.mariaalpha.marketdatagateway.model;

public enum BarTimeframe {
  ONE_MIN("1Min"),
  FIVE_MIN("5Min"),
  FIFTEEN_MIN("15Min"),
  ONE_HOUR("1Hour"),
  ONE_DAY("1Day");

  private final String label;

  BarTimeframe(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }
}
