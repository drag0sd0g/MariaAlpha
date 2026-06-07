package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.config.SymbolReferenceConfig;
import com.mariaalpha.executionengine.config.SymbolReferenceConfig.SymbolRef;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.PositionTracker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntradayVarCheckTest {

  private MarketStateTracker tracker;
  private PositionTracker positions;
  private IntradayVarCheck check;

  @BeforeEach
  void setUp() {
    tracker = mock(MarketStateTracker.class);
    positions = mock(PositionTracker.class);
    var refData = loadRefData();
    var config =
        new RiskLimitsConfig(
            100_000, 500_000, 2_000_000, 50, 25_000, Map.of(), 0L, 0L, 0.0, 750_000L, 0.95, 252.0,
            List.of());
    check = new IntradayVarCheck(config, tracker, positions, refData);
  }

  /** Sanity-check the inlined z-score table against the standard normal reference values. */
  @Test
  void zScoreMatchesStandardConfidenceLevels() {
    assertThat(IntradayVarCheck.zScore(0.95)).isCloseTo(1.6449, within(0.001));
    assertThat(IntradayVarCheck.zScore(0.99)).isCloseTo(2.3264, within(0.005));
    assertThat(IntradayVarCheck.zScore(0.90)).isCloseTo(1.2816, within(0.005));
  }

  @Test
  void passesWhenProjectedVarBelowLimit() {
    // 1000 AAPL × $200 = $200K notional × σ0.28 / √252 × 1.645 ≈ $5.8K per-position VaR
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "200"));
    when(positions.snapshot()).thenReturn(Map.of("AAPL", new BigDecimal("200000")));
    // +100 AAPL = $20K more → new position $220K → ~$6.4K VaR, well below $750K cap
    var order = order("AAPL", Side.BUY, 100);
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void failsWhenProjectedVarBreachesLimit() {
    // TSLA σ = 0.55, so per-$1 of position notional VaR ≈ 1 × 0.55 / √252 × 1.645 ≈ $0.057
    // A $20M TSLA position → ~$1.14M VaR, well over the $750K cap
    when(tracker.getMarketState("TSLA")).thenReturn(market("TSLA", "250"));
    when(positions.snapshot()).thenReturn(Map.of("TSLA", new BigDecimal("1000000")));
    // +80000 TSLA × $250 = $20M notional → existing $1M + $20M = $21M → ~$1.2M VaR > $750K cap
    var order = order("TSLA", Side.BUY, 80_000);
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("VaR");
  }

  @Test
  void sellsThatReduceVarPass() {
    // Sitting on a $20M TSLA long → ~$1.14M VaR, already above the cap. A SELL that pulls it
    // toward zero must still pass — the check only fires when the projection grows.
    when(tracker.getMarketState("TSLA")).thenReturn(market("TSLA", "250"));
    when(positions.snapshot()).thenReturn(Map.of("TSLA", new BigDecimal("20000000")));
    var order = order("TSLA", Side.SELL, 1000);
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void disabledWhenLimitIsZero() {
    var refData = loadRefData();
    var disabled =
        new IntradayVarCheck(
            new RiskLimitsConfig(
                100_000, 500_000, 2_000_000, 50, 25_000, Map.of(), 0L, 0L, 0.0, 0L, 0.95, 252.0,
                List.of()),
            tracker,
            positions,
            refData);
    when(tracker.getMarketState("TSLA")).thenReturn(market("TSLA", "250"));
    when(positions.snapshot()).thenReturn(Map.of());
    assertThat(disabled.check(order("TSLA", Side.BUY, 1_000_000)).passed()).isTrue();
  }

  @Test
  void unknownSymbolVolatilityContributesZero() {
    // ZZZZ has no reference data → default σ = 0 → check sees $0 VaR contribution. A BUY of any
    // size therefore passes even with massive notional.
    when(tracker.getMarketState("ZZZZ")).thenReturn(market("ZZZZ", "100"));
    when(positions.snapshot()).thenReturn(Map.of());
    assertThat(check.check(order("ZZZZ", Side.BUY, 100_000_000)).passed()).isTrue();
  }

  @Test
  void failsWhenMarketDataMissing() {
    when(tracker.getMarketState("AAPL")).thenReturn(null);
    when(positions.snapshot()).thenReturn(Map.of());
    var result = check.check(order("AAPL", Side.BUY, 100));
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("Market data unavailable");
  }

  @Test
  void portfolioVarAccumulatesAcrossSymbols() {
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "200"));
    // Two existing positions accumulate VaR; we want to push the projection just past the cap.
    // NVDA: σ=0.48 — $10M position → 10_000_000 × 0.48 / √252 × 1.645 ≈ $497K VaR.
    // TSLA: σ=0.55 — $5M position → 5_000_000 × 0.55 / √252 × 1.645 ≈ $285K VaR.
    // Sum ≈ $782K — already over the $750K cap.
    when(positions.snapshot())
        .thenReturn(
            Map.of(
                "NVDA", new BigDecimal("10000000"),
                "TSLA", new BigDecimal("5000000")));
    // BUY 100 AAPL adds tiny extra → projection grows → must fail.
    var result = check.check(order("AAPL", Side.BUY, 100));
    assertThat(result.passed()).isFalse();
  }

  private static SymbolReferenceData loadRefData() {
    var cfg =
        new SymbolReferenceConfig(
            List.of(
                new SymbolRef("AAPL", "TECH", 1.20, 60_000_000L, 0.28),
                new SymbolRef("MSFT", "TECH", 0.95, 25_000_000L, 0.24),
                new SymbolRef("NVDA", "TECH", 1.65, 250_000_000L, 0.48),
                new SymbolRef("TSLA", "AUTOMOTIVE", 1.80, 90_000_000L, 0.55)),
            new SymbolRef("*", "UNKNOWN", 1.0, 0L, 0.0));
    var data = new SymbolReferenceData(cfg);
    data.load();
    return data;
  }

  private static MarketState market(String symbol, String price) {
    return new MarketState(
        symbol, new BigDecimal(price), new BigDecimal(price), new BigDecimal(price), Instant.now());
  }

  private static Order order(String symbol, Side side, int qty) {
    return new Order(
        new OrderSignal(symbol, side, qty, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
