package com.mariaalpha.executionengine.router.scorer;

import org.springframework.stereotype.Component;

@Component
public class LatencyScorer implements VenueScoreCriterion {

  @Override
  public String name() {
    return "Latency";
  }

  @Override
  public double score(ScoringContext ctx) {
    var max = Math.max(1, ctx.config().maxLatencyMs());
    var latency = Math.max(0, ctx.venue().avgLatencyMs());
    var score = 1.0 - ((double) latency / max);
    return Math.clamp(score, 0.0, 1.0);
  }
}
