package com.mariaalpha.marketdatagateway.book;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.EventType;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderBookEntryTest {

  @Test
  void emptyEntryHasZeroValues() {
    var entry = OrderBookEntry.empty("AAPL");

    assertThat(entry.symbol()).isEqualTo("AAPL");
    assertThat(entry.bidPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(entry.askPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(entry.bidSize()).isZero();
    assertThat(entry.askSize()).isZero();
    assertThat(entry.lastPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(entry.cumulativeVolume()).isZero();
  }

  @Test
  void tradeTickUpdatesLastPriceAndVolume() {
    var entry = OrderBookEntry.empty("AAPL");
    var tick = tradeTick("AAPL", "178.52", 100, 1000000);

    var updated = entry.update(tick);

    assertThat(updated.lastPrice()).isEqualByComparingTo(new BigDecimal("178.52"));
    assertThat(updated.cumulativeVolume()).isEqualTo(1000000L);
  }

  @Test
  void tradeTickDoesNotOverwriteBidAsk() {
    var entry =
        new OrderBookEntry(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            200L,
            150L,
            BigDecimal.ZERO,
            0L,
            Instant.EPOCH);
    var tick = tradeTick("AAPL", "178.52", 100, 1000000);

    var updated = entry.update(tick);

    assertThat(updated.bidPrice()).isEqualByComparingTo(new BigDecimal("178.50"));
    assertThat(updated.askPrice()).isEqualByComparingTo(new BigDecimal("178.54"));
    assertThat(updated.bidSize()).isEqualTo(200L);
    assertThat(updated.askSize()).isEqualTo(150L);
  }

  @Test
  void quoteTickUpdatesBidAsk() {
    var entry = OrderBookEntry.empty("AAPL");
    var tick = quoteTick("AAPL", "178.50", "178.54", 200, 150);

    var updated = entry.update(tick);

    assertThat(updated.bidPrice()).isEqualByComparingTo(new BigDecimal("178.50"));
    assertThat(updated.askPrice()).isEqualByComparingTo(new BigDecimal("178.54"));
    assertThat(updated.bidSize()).isEqualTo(200L);
    assertThat(updated.askSize()).isEqualTo(150L);
  }

  @Test
  void quoteTickDoesNotOverwriteLastPrice() {
    var entry =
        new OrderBookEntry(
            "AAPL",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0L,
            0L,
            new BigDecimal("178.52"),
            1000000L,
            Instant.EPOCH);
    var tick = quoteTick("AAPL", "178.50", "178.54", 200, 150);

    var updated = entry.update(tick);

    assertThat(updated.lastPrice()).isEqualByComparingTo(new BigDecimal("178.52"));
    assertThat(updated.cumulativeVolume()).isEqualTo(1000000L);
  }

  @Test
  void barTickIsIgnored() {
    var entry =
        new OrderBookEntry(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            200L,
            150L,
            new BigDecimal("178.52"),
            1000000L,
            Instant.parse("2026-03-24T14:30:00Z"));
    var tick =
        new MarketTick(
            "AAPL",
            Instant.parse("2026-03-24T14:31:00Z"),
            EventType.BAR,
            new BigDecimal("179.00"),
            500L,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0L,
            0L,
            1000500L,
            DataSource.ALPACA);

    var updated = entry.update(tick);

    assertThat(updated).isSameAs(entry);
  }

  @Test
  void sequentialTradeAndQuoteBuildsFullBook() {
    var entry = OrderBookEntry.empty("AAPL");

    entry = entry.update(tradeTick("AAPL", "178.52", 100, 1000000));
    entry = entry.update(quoteTick("AAPL", "178.50", "178.54", 200, 150));

    assertThat(entry.lastPrice()).isEqualByComparingTo(new BigDecimal("178.52"));
    assertThat(entry.cumulativeVolume()).isEqualTo(1000000L);
    assertThat(entry.bidPrice()).isEqualByComparingTo(new BigDecimal("178.50"));
    assertThat(entry.askPrice()).isEqualByComparingTo(new BigDecimal("178.54"));
    assertThat(entry.bidSize()).isEqualTo(200L);
    assertThat(entry.askSize()).isEqualTo(150L);
  }

  private static MarketTick tradeTick(String symbol, String price, long size, long volume) {
    return new MarketTick(
        symbol,
        Instant.parse("2026-03-24T14:30:00.123Z"),
        EventType.TRADE,
        new BigDecimal(price),
        size,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0L,
        0L,
        volume,
        DataSource.ALPACA);
  }

  private static MarketTick quoteTick(
      String symbol, String bid, String ask, long bidSize, long askSize) {
    return new MarketTick(
        symbol,
        Instant.parse("2026-03-24T14:30:00.123Z"),
        EventType.QUOTE,
        BigDecimal.ZERO,
        0L,
        new BigDecimal(bid),
        new BigDecimal(ask),
        bidSize,
        askSize,
        0L,
        DataSource.ALPACA);
  }
}
