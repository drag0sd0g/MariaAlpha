package com.mariaalpha.executionengine.router.scorer;

import org.springframework.stereotype.Component;

@Component
public class InformationLeakageScorer implements VenueScoreCriterion {

  @Override
  public String name() {
    return "InformationLeakage";
  }

  @Override
  public double score(ScoringContext ctx) {
    return 1.0 - Math.clamp(ctx.venue().leakageScore(), 0.0, 1.0);
  }
}
