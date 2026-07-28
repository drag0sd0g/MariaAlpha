package com.mariaalpha.strategyengine.backtest;

import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronous, clock-stamped fill simulator that mirrors the semantics of the production {@code
 * SimulatedExchangeAdapter} but runs deterministically inside the backtest loop.
 *
 * <p>MARKET orders and marketable LIMIT orders cross the spread immediately and pay {@code
 * slippageBps} against the touch. A LIMIT order that is not yet marketable rests and fills — at its
 * limit price, with no slippage, reflecting passive execution — once a later tick prints through
 * it. Fills are full-size; partial-fill modelling is intentionally out of scope so P&L attribution
 * stays unambiguous.
 */
public class BacktestFillModel {

  private final BigDecimal slippageFraction;
  private final Map<String, List<Resting>> restingBySymbol = new HashMap<>();

  public BacktestFillModel(double slippageBps) {
    this.slippageFraction =
        BigDecimal.valueOf(slippageBps).divide(BigDecimal.valueOf(10000), 10, RoundingMode.HALF_UP);
  }

  /** Handles a freshly emitted signal, returning an immediate fill or resting the order. */
  public List<BacktestFill> onSignal(OrderSignal signal, MarketTick tick, Instant now) {
    var arrivalMid = mid(tick);
    boolean marketOrder = signal.orderType() == OrderType.MARKET;
    if (marketOrder || marketable(signal, tick)) {
      return List.of(aggressiveFill(signal, tick, now, arrivalMid));
    }
    restingBySymbol
        .computeIfAbsent(signal.symbol(), k -> new ArrayList<>())
        .add(new Resting(signal, arrivalMid));
    return List.of();
  }

  /** Fills any resting limit orders for the tick's symbol that have become marketable. */
  public List<BacktestFill> onTick(MarketTick tick, Instant now) {
    var resting = restingBySymbol.get(tick.symbol());
    if (resting == null || resting.isEmpty()) {
      return List.of();
    }
    var fills = new ArrayList<BacktestFill>();
    var iterator = resting.iterator();
    while (iterator.hasNext()) {
      var order = iterator.next();
      if (marketable(order.signal(), tick)) {
        fills.add(passiveFill(order.signal(), now, order.arrivalMid()));
        iterator.remove();
      }
    }
    return fills;
  }

  private BacktestFill aggressiveFill(
      OrderSignal signal, MarketTick tick, Instant now, BigDecimal arrivalMid) {
    var touch = signal.side() == Side.BUY ? tick.askPrice() : tick.bidPrice();
    var slippage = touch.multiply(slippageFraction);
    var price = signal.side() == Side.BUY ? touch.add(slippage) : touch.subtract(slippage);
    return new BacktestFill(
        signal.symbol(),
        signal.side(),
        signal.quantity(),
        price.setScale(4, RoundingMode.HALF_UP),
        now,
        arrivalMid,
        false);
  }

  private BacktestFill passiveFill(OrderSignal signal, Instant now, BigDecimal arrivalMid) {
    return new BacktestFill(
        signal.symbol(),
        signal.side(),
        signal.quantity(),
        signal.limitPrice().setScale(4, RoundingMode.HALF_UP),
        now,
        arrivalMid,
        true);
  }

  private static boolean marketable(OrderSignal signal, MarketTick tick) {
    if (signal.limitPrice() == null) {
      return true;
    }
    return signal.side() == Side.BUY
        ? signal.limitPrice().compareTo(tick.askPrice()) >= 0
        : signal.limitPrice().compareTo(tick.bidPrice()) <= 0;
  }

  private static BigDecimal mid(MarketTick tick) {
    var bid = tick.bidPrice();
    var ask = tick.askPrice();
    if (bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0) {
      return bid.add(ask).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }
    return tick.price();
  }

  private record Resting(OrderSignal signal, BigDecimal arrivalMid) {}
}
