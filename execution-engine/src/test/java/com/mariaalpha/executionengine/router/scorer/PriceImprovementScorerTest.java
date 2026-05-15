package com.mariaalpha.executionengine.router.scorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.router.Venue;
import com.mariaalpha.executionengine.router.VenueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceImprovementScorerTest {

  private final PriceImprovementScorer scorer = new PriceImprovementScorer();
  private final SorConfig config =
      new SorConfig(
          "scored",
          200,
          10,
          5,
          1000,
          new SorConfig.Weights(0.25, 0.25, 0.10, 0.15, 0.25),
          List.of());

  @Test
  void litVenueHasZero() {
    var ctx = ctx(VenueType.LIT, market("178.50", "178.54"));
    assertThat(scorer.score(ctx)).isEqualTo(0.0);
  }

  @Test
  void darkVenueGetsHalfSpread() {
    // bid=178.50 ask=178.54 → spread=0.04, half=0.02, mid≈178.52, halfSpreadBps≈1.12
    // max=5 bps → score ≈ 0.224
    var ctx = ctx(VenueType.DARK, market("178.50", "178.54"));
    assertThat(scorer.score(ctx)).isCloseTo(0.224, within(0.01));
  }

  @Test
  void internalVenueGetsHalfSpread() {
    var ctx = ctx(VenueType.INTERNAL, market("178.50", "178.54"));
    assertThat(scorer.score(ctx)).isCloseTo(0.224, within(0.01));
  }

  @Test
  void nullMarketStateGivesNeutral() {
    var ctx = ctx(VenueType.DARK, null);
    assertThat(scorer.score(ctx)).isEqualTo(0.5);
  }

  @Test
  void capsAtOneForWideSpread() {
    // bid=100 ask=101 → halfSpreadBps≈49.75; max=5 → raw=9.95 → cap=1.0
    var ctx = ctx(VenueType.DARK, market("100.00", "101.00"));
    assertThat(scorer.score(ctx)).isEqualTo(1.0);
  }

  private ScoringContext ctx(VenueType type, MarketState market) {
    return new ScoringContext(
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "TEST", Instant.now())),
        new Venue("V", type, 50, 3, 2, 1.0, 10000, 0.95, "primary", true),
        market,
        config);
  }

  private MarketState market(String bid, String ask) {
    return new MarketState(
        "AAPL", new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(ask), Instant.now());
  }
}
