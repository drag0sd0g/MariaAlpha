package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
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

class BetaExposureCheckTest {

  private MarketStateTracker tracker;
  private PositionTracker positions;
  private BetaExposureCheck check;

  @BeforeEach
  void setUp() {
    tracker = mock(MarketStateTracker.class);
    positions = mock(PositionTracker.class);
    var refData = loadRefData();
    var config =
        new RiskLimitsConfig(
            100_000, 500_000, 2_000_000, 50, 25_000, Map.of(), 0L, 2_500_000L, 0.0);
    check = new BetaExposureCheck(config, tracker, positions, refData);
  }

  @Test
  void passesWhenProjectedBetaExposureBelowLimit() {
    when(tracker.getMarketState("MSFT")).thenReturn(market("MSFT", "400"));
    // Current portfolio: $1M MSFT × β0.95 = $950K beta-weighted.
    when(positions.snapshot()).thenReturn(Map.of("MSFT", new BigDecimal("1000000")));
    // +250 MSFT × $400 = $100K notional × β0.95 = $95K → projected $1.045M < $2.5M.
    var order = order("MSFT", Side.BUY, 250);
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void failsWhenProjectedBetaExposureBreachesLimit() {
    when(tracker.getMarketState("TSLA")).thenReturn(market("TSLA", "250"));
    // Existing $1M TSLA × β1.8 = $1.8M. +5000 TSLA × $250 = $1.25M × 1.8 = $2.25M.
    // Projected $1.8M + $2.25M = $4.05M > $2.5M.
    when(positions.snapshot()).thenReturn(Map.of("TSLA", new BigDecimal("1000000")));
    var order = order("TSLA", Side.BUY, 5000);
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("beta-weighted");
  }

  @Test
  void sellsThatReduceBetaExposurePass() {
    // Long $5M TSLA × β1.8 = $9M beta exposure (way over). SELL should still pass — it pulls
    // exposure toward zero.
    when(tracker.getMarketState("TSLA")).thenReturn(market("TSLA", "250"));
    when(positions.snapshot()).thenReturn(Map.of("TSLA", new BigDecimal("5000000")));
    var order = order("TSLA", Side.SELL, 1000);
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void disabledWhenLimitIsZero() {
    var refData = loadRefData();
    var disabledConfig =
        new RiskLimitsConfig(100_000, 500_000, 2_000_000, 50, 25_000, Map.of(), 0L, 0L, 0.0);
    var disabled = new BetaExposureCheck(disabledConfig, tracker, positions, refData);
    when(tracker.getMarketState("TSLA")).thenReturn(market("TSLA", "250"));
    when(positions.snapshot()).thenReturn(Map.of());
    var order = order("TSLA", Side.BUY, 100_000);
    assertThat(disabled.check(order).passed()).isTrue();
  }

  @Test
  void unknownSymbolUsesDefaultBeta() {
    when(tracker.getMarketState("ZZZZ")).thenReturn(market("ZZZZ", "100"));
    when(positions.snapshot()).thenReturn(Map.of());
    // Default β = 1.0. Order = 30K × $100 = $3M × 1.0 = $3M projected. > $2.5M → fail.
    var order = order("ZZZZ", Side.BUY, 30_000);
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
  }

  @Test
  void failsWhenMarketDataMissing() {
    when(tracker.getMarketState("AAPL")).thenReturn(null);
    when(positions.snapshot()).thenReturn(Map.of());
    var order = order("AAPL", Side.BUY, 100);
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("Market data unavailable");
  }

  private static SymbolReferenceData loadRefData() {
    var cfg =
        new SymbolReferenceConfig(
            List.of(
                new SymbolRef("AAPL", "TECH", 1.20, 60_000_000L),
                new SymbolRef("MSFT", "TECH", 0.95, 25_000_000L),
                new SymbolRef("TSLA", "AUTOMOTIVE", 1.80, 90_000_000L)),
            new SymbolRef("*", "UNKNOWN", 1.0, 0L));
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
