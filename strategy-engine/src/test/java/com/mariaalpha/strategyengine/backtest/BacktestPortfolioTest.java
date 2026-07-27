package com.mariaalpha.strategyengine.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BacktestPortfolioTest {

  private static final Instant T = Instant.parse("2026-03-24T14:30:00Z");
  private static final BigDecimal START = BigDecimal.valueOf(100_000);

  private static BacktestFill fill(Side side, int qty, String price) {
    return new BacktestFill(
        "AAPL", side, qty, new BigDecimal(price), T, new BigDecimal(price), false);
  }

  @Test
  void longRoundTripRealisesProfitAndCountsAsWin() {
    var portfolio = new BacktestPortfolio(START);

    var openPnl = portfolio.apply(fill(Side.BUY, 100, "10.00"));
    assertThat(openPnl).isEqualByComparingTo("0");
    assertThat(portfolio.netQuantity("AAPL")).isEqualTo(100);
    assertThat(portfolio.equity(Map.of("AAPL", new BigDecimal("10.00"))))
        .isEqualByComparingTo(START);

    var closePnl = portfolio.apply(fill(Side.SELL, 100, "12.00"));

    assertThat(closePnl).isEqualByComparingTo("200.00");
    assertThat(portfolio.realizedPnl()).isEqualByComparingTo("200.00");
    assertThat(portfolio.closedTrades()).isEqualTo(1);
    assertThat(portfolio.winningTrades()).isEqualTo(1);
    assertThat(portfolio.netQuantity("AAPL")).isZero();
    assertThat(portfolio.equity(Map.of())).isEqualByComparingTo("100200.00");
  }

  @Test
  void shortRoundTripRealisesProfitWhenPriceFalls() {
    var portfolio = new BacktestPortfolio(START);

    portfolio.apply(fill(Side.SELL, 100, "10.00"));
    assertThat(portfolio.netQuantity("AAPL")).isEqualTo(-100);

    var pnl = portfolio.apply(fill(Side.BUY, 100, "8.00"));

    assertThat(pnl).isEqualByComparingTo("200.00");
    assertThat(portfolio.winningTrades()).isEqualTo(1);
  }

  @Test
  void losingTradeIsNotCountedAsWin() {
    var portfolio = new BacktestPortfolio(START);
    portfolio.apply(fill(Side.BUY, 100, "10.00"));

    var pnl = portfolio.apply(fill(Side.SELL, 100, "9.00"));

    assertThat(pnl).isEqualByComparingTo("-100.00");
    assertThat(portfolio.closedTrades()).isEqualTo(1);
    assertThat(portfolio.winningTrades()).isZero();
  }

  @Test
  void reducingThenFlippingResetsAverageCostToFillPrice() {
    var portfolio = new BacktestPortfolio(START);
    portfolio.apply(fill(Side.BUY, 100, "10.00"));

    var pnl = portfolio.apply(fill(Side.SELL, 150, "12.00"));

    // Closes 100 long at +2, then opens 50 short at 12.
    assertThat(pnl).isEqualByComparingTo("200.00");
    assertThat(portfolio.netQuantity("AAPL")).isEqualTo(-50);
    // Unrealized on the new short: (12 - mark) * -50; at mark 12 it is flat.
    assertThat(portfolio.unrealized(Map.of("AAPL", new BigDecimal("12.00"))))
        .isEqualByComparingTo("0");
    assertThat(portfolio.unrealized(Map.of("AAPL", new BigDecimal("11.00"))))
        .isEqualByComparingTo("50.00");
  }

  @Test
  void addingToPositionBlendsAverageCost() {
    var portfolio = new BacktestPortfolio(START);
    portfolio.apply(fill(Side.BUY, 100, "10.00"));
    portfolio.apply(fill(Side.BUY, 100, "12.00"));

    // avg cost now 11; selling 200 at 11 realises zero.
    var pnl = portfolio.apply(fill(Side.SELL, 200, "11.00"));
    assertThat(pnl).isEqualByComparingTo("0");
    assertThat(portfolio.netQuantity("AAPL")).isZero();
  }
}
