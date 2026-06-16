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

class SectorExposureCheckTest {

  private MarketStateTracker tracker;
  private PositionTracker positions;
  private SectorExposureCheck check;

  @BeforeEach
  void setUp() {
    tracker = mock(MarketStateTracker.class);
    positions = mock(PositionTracker.class);
    var refData = loadRefData();
    var config =
        new RiskLimitsConfig(
            100_000,
            500_000,
            2_000_000,
            50,
            25_000,
            Map.of("TECH", 1_500_000L, "AUTOMOTIVE", 750_000L),
            1_000_000L,
            0L,
            0.0);
    check = new SectorExposureCheck(config, tracker, positions, refData);
  }

  @Test
  void passesWhenProjectedSectorExposureBelowLimit() {
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "150"));
    when(positions.snapshot())
        .thenReturn(Map.of("AAPL", new BigDecimal("500000"), "MSFT", new BigDecimal("400000")));
    var order = order("AAPL", Side.BUY, 1000);
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void failsWhenSectorLimitBreached() {
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "150"));
    when(positions.snapshot())
        .thenReturn(Map.of("AAPL", new BigDecimal("500000"), "NVDA", new BigDecimal("900000")));
    when(tracker.getMarketState("NVDA")).thenReturn(market("NVDA", "200"));
    var order = order("NVDA", Side.BUY, 1000);
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("TECH");
  }

  @Test
  void sellsThatReduceSectorExposurePass() {
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "150"));
    when(positions.snapshot()).thenReturn(Map.of("AAPL", new BigDecimal("1400000")));
    var order = order("AAPL", Side.SELL, 1000);
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void unknownSectorFallsBackToDefaultLimit() {
    when(tracker.getMarketState("ZZZZ")).thenReturn(market("ZZZZ", "100"));
    when(positions.snapshot()).thenReturn(Map.of());
    var bigOrder = order("ZZZZ", Side.BUY, 20_000);
    var result = check.check(bigOrder);
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("UNKNOWN");
  }

  @Test
  void disabledWhenLimitIsZeroAndNoDefault() {
    var refData = loadRefData();
    var disabledConfig =
        new RiskLimitsConfig(100_000, 500_000, 2_000_000, 50, 25_000, Map.of(), 0L, 0L, 0.0);
    var disabled = new SectorExposureCheck(disabledConfig, tracker, positions, refData);
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "150"));
    when(positions.snapshot()).thenReturn(Map.of());
    var order = order("AAPL", Side.BUY, 100_000);
    assertThat(disabled.check(order).passed()).isTrue();
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
                new SymbolRef("AAPL", "TECH", 1.2, 60_000_000L),
                new SymbolRef("MSFT", "TECH", 0.95, 25_000_000L),
                new SymbolRef("NVDA", "TECH", 1.65, 250_000_000L),
                new SymbolRef("TSLA", "AUTOMOTIVE", 1.8, 90_000_000L)),
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
