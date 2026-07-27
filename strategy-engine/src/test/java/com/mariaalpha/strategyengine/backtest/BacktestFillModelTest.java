package com.mariaalpha.strategyengine.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BacktestFillModelTest {

  private static final Instant T = Instant.parse("2026-03-24T14:30:00Z");

  private static MarketTick tick(String bid, String ask, String last) {
    return new MarketTick(
        "AAPL",
        T,
        EventType.TRADE,
        new BigDecimal(last),
        100,
        new BigDecimal(bid),
        new BigDecimal(ask),
        100,
        100,
        1000,
        DataSource.ALPACA,
        false);
  }

  private static OrderSignal signal(Side side, OrderType type, String limit) {
    return new OrderSignal(
        "AAPL", side, 100, type, limit == null ? null : new BigDecimal(limit), "MOMENTUM", T);
  }

  @Test
  void marketBuyPaysSlippageAboveTheAsk() {
    var model = new BacktestFillModel(10.0); // 10 bps
    var fills =
        model.onSignal(
            signal(Side.BUY, OrderType.MARKET, null), tick("99.90", "100.10", "100.00"), T);

    assertThat(fills).hasSize(1);
    var fill = fills.get(0);
    assertThat(fill.passive()).isFalse();
    // 100.10 + 10bps = 100.10 * 1.001 = 100.2001
    assertThat(fill.price()).isEqualByComparingTo("100.2001");
  }

  @Test
  void marketSellPaysSlippageBelowTheBid() {
    var model = new BacktestFillModel(10.0);
    var fills =
        model.onSignal(
            signal(Side.SELL, OrderType.MARKET, null), tick("99.90", "100.10", "100.00"), T);

    // 99.90 - 10bps = 99.90 * 0.999 = 99.8001
    assertThat(fills.get(0).price()).isEqualByComparingTo("99.8001");
  }

  @Test
  void marketableLimitBuyFillsImmediatelyAndAggressively() {
    var model = new BacktestFillModel(0.0);
    var fills =
        model.onSignal(
            signal(Side.BUY, OrderType.LIMIT, "100.20"), tick("99.90", "100.10", "100.00"), T);

    assertThat(fills).hasSize(1);
    assertThat(fills.get(0).passive()).isFalse();
    assertThat(fills.get(0).price()).isEqualByComparingTo("100.10");
  }

  @Test
  void nonMarketableLimitRestsThenFillsPassivelyWhenPriceCrosses() {
    var model = new BacktestFillModel(5.0);
    // Buy limit 99.50 while ask is 100.10 — not marketable, so it rests.
    var immediate =
        model.onSignal(
            signal(Side.BUY, OrderType.LIMIT, "99.50"), tick("99.90", "100.10", "100.00"), T);
    assertThat(immediate).isEmpty();

    // Ask stays above the limit — still no fill.
    assertThat(model.onTick(tick("99.60", "99.80", "99.70"), T)).isEmpty();

    // Ask drops to the limit — passive fill at the limit price, no slippage.
    var fills = model.onTick(tick("99.30", "99.50", "99.40"), T);
    assertThat(fills).hasSize(1);
    assertThat(fills.get(0).passive()).isTrue();
    assertThat(fills.get(0).price()).isEqualByComparingTo("99.50");
  }
}
