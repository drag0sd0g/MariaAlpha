package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.config.RiskLimitsConfig.CorrelatedCluster;
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

class CorrelatedPositionsCheckTest {

  private static final List<CorrelatedCluster> DEFAULT_CLUSTERS =
      List.of(
          new CorrelatedCluster("MEGACAP_TECH", List.of("AAPL", "MSFT", "GOOGL"), 1_750_000L),
          new CorrelatedCluster("AI_TRADE", List.of("NVDA", "MSFT", "GOOGL"), 1_500_000L));

  private MarketStateTracker tracker;
  private PositionTracker positions;
  private CorrelatedPositionsCheck check;

  @BeforeEach
  void setUp() {
    tracker = mock(MarketStateTracker.class);
    positions = mock(PositionTracker.class);
    check =
        new CorrelatedPositionsCheck(configWith(DEFAULT_CLUSTERS), tracker, positions);
  }

  @Test
  void passesWhenProjectedClusterGrossBelowLimit() {
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "200"));
    when(positions.snapshot())
        .thenReturn(
            Map.of(
                "AAPL", new BigDecimal("500000"),
                "MSFT", new BigDecimal("400000")));
    // +1000 AAPL × $200 = $200K → MEGACAP_TECH projection $200K + $500K + $400K = $1.1M < $1.75M
    assertThat(check.check(order("AAPL", Side.BUY, 1000)).passed()).isTrue();
  }

  @Test
  void failsWhenProjectedClusterGrossBreachesLimit() {
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "200"));
    when(positions.snapshot())
        .thenReturn(
            Map.of(
                "AAPL", new BigDecimal("800000"),
                "MSFT", new BigDecimal("600000")));
    // +5000 AAPL × $200 = $1M → MEGACAP_TECH projection $1M + $800K + $600K = $2.4M > $1.75M
    var result = check.check(order("AAPL", Side.BUY, 5000));
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("MEGACAP_TECH");
  }

  @Test
  void sellThatReducesClusterGrossPasses() {
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "200"));
    // Cluster is already over the cap ($2M AAPL + $400K MSFT = $2.4M > $1.75M). A SELL that pulls
    // AAPL down should still pass — projection grows toward zero, never above the current $2.4M.
    when(positions.snapshot())
        .thenReturn(
            Map.of(
                "AAPL", new BigDecimal("2000000"),
                "MSFT", new BigDecimal("400000")));
    assertThat(check.check(order("AAPL", Side.SELL, 1000)).passed()).isTrue();
  }

  @Test
  void crossClusterMembershipIsCheckedIndependently() {
    // MSFT belongs to both MEGACAP_TECH ($1.75M cap) and AI_TRADE ($1.5M cap). Position:
    //   MSFT $1M + GOOGL $400K + NVDA $1.2M.
    //   MEGACAP_TECH gross = MSFT + GOOGL = $1.4M; +400K MSFT BUY → $1.8M > $1.75M  ✗
    //   AI_TRADE gross = MSFT + GOOGL + NVDA = $2.6M; +400K MSFT BUY → $3M > $1.5M  ✗
    // First cluster checked fails — we accept either reason but require failure.
    when(tracker.getMarketState("MSFT")).thenReturn(market("MSFT", "400"));
    when(positions.snapshot())
        .thenReturn(
            Map.of(
                "MSFT", new BigDecimal("1000000"),
                "GOOGL", new BigDecimal("400000"),
                "NVDA", new BigDecimal("1200000")));
    var result = check.check(order("MSFT", Side.BUY, 1000));
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).containsAnyOf("MEGACAP_TECH", "AI_TRADE");
  }

  @Test
  void orderOnSymbolOutsideAnyClusterIsUnconstrained() {
    when(tracker.getMarketState("AMZN")).thenReturn(market("AMZN", "180"));
    when(positions.snapshot())
        .thenReturn(
            Map.of(
                "AAPL", new BigDecimal("1700000"),
                "MSFT", new BigDecimal("40000"))); // MEGACAP_TECH at $1.74M (near cap)
    // AMZN isn't in any cluster → check passes unconditionally regardless of other positions.
    assertThat(check.check(order("AMZN", Side.BUY, 1_000_000)).passed()).isTrue();
  }

  @Test
  void disabledWhenClusterListIsEmpty() {
    var disabled = new CorrelatedPositionsCheck(configWith(List.of()), tracker, positions);
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "200"));
    when(positions.snapshot()).thenReturn(Map.of("AAPL", new BigDecimal("100000000")));
    assertThat(disabled.check(order("AAPL", Side.BUY, 1_000_000)).passed()).isTrue();
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
  void freshSymbolGetsAddedToProjection() {
    // No existing AAPL position. Cluster already at $1.7M from MSFT + GOOGL.
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "200"));
    when(positions.snapshot())
        .thenReturn(
            Map.of(
                "MSFT", new BigDecimal("1000000"),
                "GOOGL", new BigDecimal("700000")));
    // +500 AAPL × $200 = $100K → projection $1.8M > $1.75M cap → reject.
    var result = check.check(order("AAPL", Side.BUY, 500));
    assertThat(result.passed()).isFalse();
  }

  private static RiskLimitsConfig configWith(List<CorrelatedCluster> clusters) {
    return new RiskLimitsConfig(
        100_000, 500_000, 2_000_000, 50, 25_000, Map.of(), 0L, 0L, 0.0, 0L, 0.95, 252.0, clusters);
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
