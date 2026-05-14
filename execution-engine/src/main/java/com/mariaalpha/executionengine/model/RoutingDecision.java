package com.mariaalpha.executionengine.model;

import com.mariaalpha.executionengine.router.VenueScoreBreakdown;
import com.mariaalpha.executionengine.router.VenueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RoutingDecision(
    String orderId,
    String venue,
    String reason,
    Instant timestamp,
    String symbol,
    Side side,
    Integer quantity,
    OrderType orderType,
    VenueType selectedVenueType,
    Double selectedScore,
    List<VenueScoreBreakdown> candidateScores,
    Map<String, Double> weights,
    MarketSnapshot marketSnapshot) {

  public static RoutingDecision legacy(
      String orderId, String venue, String reason, Instant timestamp) {
    return new RoutingDecision(
        orderId, venue, reason, timestamp, null, null, null, null, null, null, null, null, null);
  }

  public record MarketSnapshot(
      BigDecimal bid, BigDecimal ask, BigDecimal mid, BigDecimal spreadBps) {}
}
