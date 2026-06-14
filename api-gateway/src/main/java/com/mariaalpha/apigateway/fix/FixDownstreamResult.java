package com.mariaalpha.apigateway.fix;

/**
 * Outcome of forwarding a translated FIX order (or cancel) to a downstream MariaAlpha service.
 *
 * @param accepted whether the downstream accepted the request
 * @param downstreamOrderId the internal order id returned by the downstream (null on rejection)
 * @param reason human-readable rejection reason (null when accepted)
 */
public record FixDownstreamResult(boolean accepted, String downstreamOrderId, String reason) {

  public static FixDownstreamResult accepted(String downstreamOrderId) {
    return new FixDownstreamResult(true, downstreamOrderId, null);
  }

  public static FixDownstreamResult rejected(String reason) {
    return new FixDownstreamResult(false, null, reason);
  }
}
