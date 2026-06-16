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
    var ctx = ctxMarket(3, 2);
    assertThat(scorer.score(ctx)).isCloseTo(0.7, within(1e-9));
  }

  @Test
  void passiveLimitUsesMakerRebate() {
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

  @Test
  void stopOrderIsAggressive() {
    var order =
        new Order(
            new OrderSignal(
                "AAPL",
                Side.BUY,
                100,
                OrderType.STOP,
                null,
                new BigDecimal("180.00"),
                "TEST",
                Instant.now()));
    assertThat(FeeScorer.isAggressive(order, marketState())).isTrue();
  }

  @Test
  void limitWithoutPriceIsPassive() {
    var order =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, 100, OrderType.LIMIT, null, null, "T", Instant.now()));
    assertThat(FeeScorer.isAggressive(order, marketState())).isFalse();
  }

  @Test
  void limitWithoutMarketIsPassive() {
    var order =
        new Order(
            new OrderSignal(
                "AAPL",
                Side.BUY,
                100,
                OrderType.LIMIT,
                new BigDecimal("178.60"),
                null,
                "T",
                Instant.now()));
    assertThat(FeeScorer.isAggressive(order, null)).isFalse();
  }

  @Test
  void sellLimitCrossingBidIsAggressive() {
    var order =
        new Order(
            new OrderSignal(
                "AAPL",
                Side.SELL,
                100,
                OrderType.LIMIT,
                new BigDecimal("178.40"),
                null,
                "T",
                Instant.now()));
    assertThat(FeeScorer.isAggressive(order, marketState())).isTrue();
  }

  @Test
  void sellLimitAboveBidIsPassive() {
    var order =
        new Order(
            new OrderSignal(
                "AAPL",
                Side.SELL,
                100,
                OrderType.LIMIT,
                new BigDecimal("178.80"),
                null,
                "T",
                Instant.now()));
    assertThat(FeeScorer.isAggressive(order, marketState())).isFalse();
  }

  private ScoringContext ctxMarket(int taker, int rebate) {
    return ctx(OrderType.MARKET, null, taker, rebate, marketState());
  }

  private ScoringContext ctxLimitPassive(int taker, int rebate) {
    return ctx(OrderType.LIMIT, new BigDecimal("178.30"), taker, rebate, marketState());
  }

  private ScoringContext ctxLimitAggressive(int taker, int rebate) {
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
