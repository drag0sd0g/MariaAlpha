package com.mariaalpha.executionengine.router.scorer;

import org.springframework.stereotype.Component;

@Component
public class LiquidityScorer implements VenueScoreCriterion {

  @Override
  public String name() {
    return "Liquidity";
  }

  @Override
  public double score(ScoringContext ctx) {
    var venue = ctx.venue();
    var qty = Math.max(1, ctx.order().getQuantity());
    return switch (venue.type()) {
      case LIT -> {
        var coverage = Math.min(1.0, (double) venue.topOfBookSize() / qty);
        yield Math.clamp(coverage * venue.fillRate(), 0.0, 1.0);
      }
      case DARK, INTERNAL -> Math.clamp(venue.fillRate(), 0.0, 1.0);
    };
  }
}
