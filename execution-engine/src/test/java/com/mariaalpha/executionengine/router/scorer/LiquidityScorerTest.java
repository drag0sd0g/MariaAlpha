package com.mariaalpha.executionengine.router.scorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.router.Venue;
import com.mariaalpha.executionengine.router.VenueType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiquidityScorerTest {

  private final LiquidityScorer scorer = new LiquidityScorer();
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
  void litFullCoverage() {
    var ctx = ctxLit(100, /* topOfBook */ 10000, /* fillRate */ 0.95);
    // coverage=min(1, 10000/100)=1; * 0.95 = 0.95
    assertThat(scorer.score(ctx)).isCloseTo(0.95, within(1e-9));
  }

  @Test
  void litPartialCoverage() {
    var ctx = ctxLit(10000, /* topOfBook */ 5000, /* fillRate */ 1.0);
    // coverage=0.5; * 1.0 = 0.5
    assertThat(scorer.score(ctx)).isCloseTo(0.5, within(1e-9));
  }

  @Test
  void darkUsesFillRateOnly() {
    var ctx = ctx(VenueType.DARK, 1, 0, 0.4);
    assertThat(scorer.score(ctx)).isCloseTo(0.4, within(1e-9));
  }

  @Test
  void internalUsesFillRateOnly() {
    var ctx = ctx(VenueType.INTERNAL, 1, 0, 0.6);
    assertThat(scorer.score(ctx)).isCloseTo(0.6, within(1e-9));
  }

  @Test
  void zeroQtyDoesNotDivideByZero() {
    var ctx = ctxLit(0, 100, 1.0);
    assertThat(scorer.score(ctx)).isCloseTo(1.0, within(1e-9));
  }

  private ScoringContext ctxLit(int qty, int topOfBook, double fillRate) {
    return ctx(VenueType.LIT, qty, topOfBook, fillRate);
  }

  private ScoringContext ctx(VenueType type, int qty, int topOfBook, double fillRate) {
    var order =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, qty, OrderType.MARKET, null, null, "TEST", Instant.now()));
    var venue = new Venue("V", type, 50, 3, 2, 1.0, topOfBook, fillRate, "primary", true);
    return new ScoringContext(order, venue, null, config);
  }
}
