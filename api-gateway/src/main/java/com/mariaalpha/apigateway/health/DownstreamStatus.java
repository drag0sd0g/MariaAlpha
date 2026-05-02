package com.mariaalpha.apigateway.health;

public record DownstreamStatus(String name, boolean up, String detail) {

  public static DownstreamStatus up(String name) {
    return new DownstreamStatus(name, true, "UP");
  }

  public static DownstreamStatus down(String name, String detail) {
    return new DownstreamStatus(name, false, detail);
  }
}
