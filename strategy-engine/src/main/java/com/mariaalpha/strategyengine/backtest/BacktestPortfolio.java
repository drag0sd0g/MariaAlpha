package com.mariaalpha.strategyengine.backtest;

import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Average-cost, signed-position P&L accountant for a backtest run. Applying a fill updates cash and
 * the position and realises P&L on any quantity that closes or reduces an existing position.
 *
 * <p>Equity is marked to market as {@code cash + Σ netQty·mark}, which by construction equals
 * {@code initialCash + realized + unrealized}.
 */
public class BacktestPortfolio {

  private final BigDecimal initialCash;
  private BigDecimal cash;
  private BigDecimal realizedPnl = BigDecimal.ZERO;
  private int closedTrades;
  private int winningTrades;
  private final Map<String, Position> positions = new HashMap<>();

  public BacktestPortfolio(BigDecimal initialCash) {
    this.initialCash = initialCash;
    this.cash = initialCash;
  }

  /** Applies a fill and returns the realised-P&L delta it produced (zero for pure opens/adds). */
  public BigDecimal apply(BacktestFill fill) {
    var position = positions.computeIfAbsent(fill.symbol(), key -> new Position());
    long signed = fill.side() == Side.BUY ? fill.quantity() : -fill.quantity();
    var price = fill.price();
    var qty = BigDecimal.valueOf(fill.quantity());

    cash =
        fill.side() == Side.BUY
            ? cash.subtract(price.multiply(qty))
            : cash.add(price.multiply(qty));

    long oldQty = position.netQty;
    long newQty = oldQty + signed;
    var realizedDelta = BigDecimal.ZERO;

    boolean reducing = oldQty != 0 && Long.signum(oldQty) != Long.signum(signed);
    if (reducing) {
      long closedQty = Math.min(Math.abs(oldQty), Math.abs(signed));
      var closed = BigDecimal.valueOf(closedQty);
      realizedDelta =
          oldQty > 0
              ? price.subtract(position.avgCost).multiply(closed)
              : position.avgCost.subtract(price).multiply(closed);
      realizedPnl = realizedPnl.add(realizedDelta);
      closedTrades++;
      if (realizedDelta.signum() > 0) {
        winningTrades++;
      }
      if (newQty == 0) {
        position.avgCost = BigDecimal.ZERO;
      } else if (Long.signum(newQty) != Long.signum(oldQty)) {
        position.avgCost = price;
      }
    } else if (oldQty == 0) {
      position.avgCost = price;
    } else {
      var oldAbs = BigDecimal.valueOf(Math.abs(oldQty));
      var newAbs = BigDecimal.valueOf(Math.abs(newQty));
      position.avgCost =
          position
              .avgCost
              .multiply(oldAbs)
              .add(price.multiply(qty))
              .divide(newAbs, 6, RoundingMode.HALF_UP);
    }
    position.netQty = newQty;
    return realizedDelta;
  }

  public BigDecimal equity(Map<String, BigDecimal> marks) {
    var equity = cash;
    for (var entry : positions.entrySet()) {
      var position = entry.getValue();
      var mark = marks.get(entry.getKey());
      if (position.netQty != 0 && mark != null) {
        equity = equity.add(mark.multiply(BigDecimal.valueOf(position.netQty)));
      }
    }
    return equity;
  }

  public BigDecimal unrealized(Map<String, BigDecimal> marks) {
    var unrealized = BigDecimal.ZERO;
    for (var entry : positions.entrySet()) {
      var position = entry.getValue();
      var mark = marks.get(entry.getKey());
      if (position.netQty != 0 && mark != null) {
        unrealized =
            unrealized.add(
                mark.subtract(position.avgCost).multiply(BigDecimal.valueOf(position.netQty)));
      }
    }
    return unrealized;
  }

  public BigDecimal initialCash() {
    return initialCash;
  }

  public BigDecimal cash() {
    return cash;
  }

  public BigDecimal realizedPnl() {
    return realizedPnl;
  }

  public int closedTrades() {
    return closedTrades;
  }

  public int winningTrades() {
    return winningTrades;
  }

  long netQuantity(String symbol) {
    var position = positions.get(symbol);
    return position == null ? 0 : position.netQty;
  }

  private static final class Position {
    private long netQty;
    private BigDecimal avgCost = BigDecimal.ZERO;
  }
}
