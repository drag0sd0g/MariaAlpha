package com.mariaalpha.posttrade.tca;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.posttrade.config.TcaConfig;
import com.mariaalpha.posttrade.model.DataSource;
import com.mariaalpha.posttrade.model.EventType;
import com.mariaalpha.posttrade.model.MarketTickEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketDataCacheTest {

  private TcaConfig cfg;
  private MarketDataCache cache;

  @BeforeEach
  void setUp() {
    cfg = new TcaConfig(21600, 100, 60, "http://localhost", 2000);
    cache =
        new MarketDataCache(
            cfg, Clock.fixed(Instant.parse("2026-04-20T16:00:00Z"), ZoneOffset.UTC));
  }

  private MarketTickEvent trade(String symbol, Instant ts, double price, long size) {
    return new MarketTickEvent(
        symbol,
        ts,
        EventType.TRADE,
        BigDecimal.valueOf(price),
        size,
        null,
        null,
        null,
        null,
        null,
        DataSource.SIMULATED,
        false);
  }

  private MarketTickEvent quote(String symbol, Instant ts, double bid, double ask) {
    return new MarketTickEvent(
        symbol,
        ts,
        EventType.QUOTE,
        null,
        null,
        BigDecimal.valueOf(bid),
        BigDecimal.valueOf(ask),
        100L,
        100L,
        null,
        DataSource.SIMULATED,
        false);
  }

  @Test
  void recordAndNearest_returnsExactMatch() {
    Instant t = Instant.parse("2026-04-20T09:30:00Z");
    cache.record(trade("AAPL", t, 100.00, 500));
    assertThat(cache.nearestAtOrBefore("AAPL", t))
        .map(MarketTickEvent::price)
        .contains(BigDecimal.valueOf(100.00));
  }

  @Test
  void nearestAtOrBefore_picksLatestPriorTick() {
    Instant t1 = Instant.parse("2026-04-20T09:29:55Z");
    Instant t2 = Instant.parse("2026-04-20T09:29:58Z");
    cache.record(trade("AAPL", t1, 99.00, 100));
    cache.record(trade("AAPL", t2, 99.50, 100));
    var found = cache.nearestAtOrBefore("AAPL", Instant.parse("2026-04-20T09:30:00Z"));
    assertThat(found).map(MarketTickEvent::timestamp).contains(t2);
  }

  @Test
  void nearestAtOrBefore_unknownSymbol_isEmpty() {
    assertThat(cache.nearestAtOrBefore("UNKNOWN", Instant.now())).isEmpty();
  }

  @Test
  void tradesInRange_filtersOutQuotesAndOutOfRange() {
    cache.record(quote("AAPL", Instant.parse("2026-04-20T09:30:00Z"), 99.99, 100.01));
    cache.record(trade("AAPL", Instant.parse("2026-04-20T09:31:00Z"), 100.05, 200));
    cache.record(trade("AAPL", Instant.parse("2026-04-20T09:32:00Z"), 100.10, 300));
    cache.record(trade("AAPL", Instant.parse("2026-04-20T09:40:00Z"), 100.20, 100));
    List<MarketTickEvent> trades =
        cache.tradesInRange(
            "AAPL", Instant.parse("2026-04-20T09:30:00Z"), Instant.parse("2026-04-20T09:33:00Z"));
    assertThat(trades).hasSize(2);
    assertThat(trades.get(0).size()).isEqualTo(200L);
    assertThat(trades.get(1).size()).isEqualTo(300L);
  }

  @Test
  void tradesInRange_emptyWhenStartAfterEnd() {
    cache.record(trade("AAPL", Instant.parse("2026-04-20T09:30:00Z"), 100.00, 100));
    assertThat(
            cache.tradesInRange(
                "AAPL",
                Instant.parse("2026-04-20T09:31:00Z"),
                Instant.parse("2026-04-20T09:30:00Z")))
        .isEmpty();
  }

  @Test
  void record_ignoresNullOrIncomplete() {
    cache.record(null);
    cache.record(
        new MarketTickEvent(
            null,
            Instant.now(),
            EventType.TRADE,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false));
    cache.record(
        new MarketTickEvent(
            "AAPL", null, EventType.TRADE, null, null, null, null, null, null, null, null, false));
    assertThat(cache.size("AAPL")).isZero();
  }

  @Test
  void sizeLimit_evictsOldest() {
    TcaConfig smallCfg = new TcaConfig(21600, 3, 60, "http://localhost", 2000);
    MarketDataCache small =
        new MarketDataCache(
            smallCfg, Clock.fixed(Instant.parse("2026-04-20T16:00:00Z"), ZoneOffset.UTC));
    for (int i = 0; i < 5; i++) {
      small.record(
          trade("AAPL", Instant.parse("2026-04-20T09:00:00Z").plusSeconds(i), 100 + i, 10));
    }
    assertThat(small.size("AAPL")).isEqualTo(3);
    assertThat(small.nearestAtOrBefore("AAPL", Instant.parse("2026-04-20T09:00:00Z"))).isEmpty();
  }

  @Test
  void evictStale_removesEntriesOlderThanTtl() {
    Instant now = Instant.parse("2026-04-20T16:00:00Z");
    MarketDataCache withClock =
        new MarketDataCache(
            new TcaConfig(60, 100, 60, "http://localhost", 2000), Clock.fixed(now, ZoneOffset.UTC));
    withClock.record(trade("AAPL", now.minusSeconds(120), 100.00, 100));
    withClock.record(trade("AAPL", now.minusSeconds(30), 100.05, 100));
    withClock.evictStale();
    assertThat(withClock.size("AAPL")).isEqualTo(1);
    assertThat(withClock.nearestAtOrBefore("AAPL", now).map(MarketTickEvent::price))
        .contains(BigDecimal.valueOf(100.05));
  }
}
