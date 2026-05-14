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

class FeeScorerTest {

  private final FeeScorer scorer = new FeeScorer();
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
  void marketOrderUsesTakerFee() {
    // taker=3 bps, max=10 → 1 - 0.3 = 0.7
    var ctx = ctxMarket(3, 2);
    assertThat(scorer.score(ctx)).isCloseTo(0.7, within(1e-9));
  }

  @Test
  void passiveLimitUsesMakerRebate() {
    // rebate=2 → effective=-2 → 1 - (-0.2) = 1.2 → cap 1.0
    var ctx = ctxLimitPassive(3, 2);
    assertThat(scorer.score(ctx)).isCloseTo(1.0, within(1e-9));
  }

  @Test
  void marketIsAggressive() {
    var ctx = ctxMarket(3, 2);
    assertThat(FeeScorer.isAggressive(ctx.order(), ctx.marketState())).isTrue();
  }

  @Test
  void crossingLimitIsAggressive() {
    var ctx = ctxLimitAggressive(3, 2);
    assertThat(FeeScorer.isAggressive(ctx.order(), ctx.marketState())).isTrue();
  }

  @Test
  void capAtOneForLargeRebate() {
    var ctx = ctxLimitPassive(0, 50);
    assertThat(scorer.score(ctx)).isEqualTo(1.0);
  }

  private ScoringContext ctxMarket(int taker, int rebate) {
    return ctx(OrderType.MARKET, null, taker, rebate, marketState());
  }

  private ScoringContext ctxLimitPassive(int taker, int rebate) {
    // BUY LIMIT @ 178.30 with ask=178.54 → does not cross → passive
    return ctx(OrderType.LIMIT, new BigDecimal("178.30"), taker, rebate, marketState());
  }

  private ScoringContext ctxLimitAggressive(int taker, int rebate) {
    // BUY LIMIT @ 178.60 with ask=178.54 → crosses → aggressive
    return ctx(OrderType.LIMIT, new BigDecimal("178.60"), taker, rebate, marketState());
  }

  private ScoringContext ctx(
      OrderType type, BigDecimal limit, int taker, int rebate, MarketState m) {
    var order =
        new Order(new OrderSignal("AAPL", Side.BUY, 100, type, limit, null, "TEST", Instant.now()));
    var venue = new Venue("V", VenueType.LIT, 50, taker, rebate, 1.0, 10000, 0.95, "primary", true);
    return new ScoringContext(order, venue, m, config);
  }

  private MarketState marketState() {
    return new MarketState(
        "AAPL",
        new BigDecimal("178.50"),
        new BigDecimal("178.54"),
        new BigDecimal("178.52"),
        Instant.now());
  }
}
