package com.mariaalpha.executionengine.router.scorer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class PriceImprovementScorer implements VenueScoreCriterion {

  private static final double NEUTRAL_NO_DATA = 0.5;

  @Override
  public String name() {
    return "PriceImprovement";
  }

  @Override
  public double score(ScoringContext ctx) {
    var market = ctx.marketState();
    if (market == null || market.bidPrice() == null || market.askPrice() == null) {
      return NEUTRAL_NO_DATA;
    }

    var bid = market.bidPrice();
    var ask = market.askPrice();
    if (bid.signum() <= 0 || ask.signum() <= 0 || ask.compareTo(bid) <= 0) {
      return NEUTRAL_NO_DATA;
    }

    var spread = ask.subtract(bid);
    var mid = bid.add(ask).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    var halfSpreadBps =
        spread
            .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP)
            .divide(mid, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(10_000));
    var piBps =
        switch (ctx.venue().type()) {
          case LIT -> 0.0;
          case DARK, INTERNAL -> halfSpreadBps.doubleValue();
        };
    var max = Math.max(1, ctx.config().maxPriceImprovementBps());
    return Math.clamp(piBps / max, 0.0, 1.0);
  }
}
