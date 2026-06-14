package com.mariaalpha.marketdatagateway.book;

import com.mariaalpha.marketdatagateway.model.MarketTick;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderBookEntry(
    String symbol,
    BigDecimal bidPrice,
    BigDecimal askPrice,
    long bidSize,
    long askSize,
    BigDecimal lastPrice,
    long cumulativeVolume,
    Instant lastUpdated) {
  public static OrderBookEntry empty(String symbol) {
    return new OrderBookEntry(
        symbol, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, BigDecimal.ZERO, 0L, Instant.EPOCH);
  }

  /**
   * Returns a new entry with fields updated from the given tick. TRADE ticks update lastPrice and
   * cumulativeVolume. QUOTE ticks update bid/ask prices and sizes. BAR ticks are ignored.
   *
   * <p>Cumulative volume: the simulated CSV feed carries a running total on each tick, but the
   * Alpaca feed reports only the per-trade size (cumulativeVolume arrives as 0). Taking the max of
   * "feed-reported total" and "our own running total + this trade's size" handles both without
   * double-counting.
   */
  public OrderBookEntry update(MarketTick tick) {
    return switch (tick.eventType()) {
      case TRADE ->
          new OrderBookEntry(
              symbol,
              bidPrice,
              askPrice,
              bidSize,
              askSize,
              tick.price(),
              Math.max(tick.cumulativeVolume(), cumulativeVolume + tick.size()),
              tick.timestamp());
      case QUOTE ->
          new OrderBookEntry(
              symbol,
              tick.bidPrice(),
              tick.askPrice(),
              tick.bidSize(),
              tick.askSize(),
              lastPrice,
              cumulativeVolume,
              tick.timestamp());
      case BAR -> this;
    };
  }
}
