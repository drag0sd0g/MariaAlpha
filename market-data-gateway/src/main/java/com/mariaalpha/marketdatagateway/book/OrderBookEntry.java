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
