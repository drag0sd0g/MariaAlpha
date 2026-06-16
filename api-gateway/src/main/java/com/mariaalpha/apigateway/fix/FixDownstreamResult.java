package com.mariaalpha.apigateway.fix;

public record FixDownstreamResult(boolean accepted, String downstreamOrderId, String reason) {

  public static FixDownstreamResult accepted(String downstreamOrderId) {
    return new FixDownstreamResult(true, downstreamOrderId, null);
  }

  public static FixDownstreamResult rejected(String reason) {
    return new FixDownstreamResult(false, null, reason);
  }
}
