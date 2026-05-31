package com.mariaalpha.strategyengine.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarketStateCacheTest {

  private static final RfqPricingConfig CONFIG =
      new RfqPricingConfig(4.0, 1.0, 1_000_000.0, 30.0, 0.5, 0.3, 10_000L, "http://x", 500L, 5);

  @Test
  void emptyCacheReturnsNoSnapshotOrHistory() {
    var cache = new MarketStateCache(CONFIG);
    assertThat(cache.snapshot("AAPL")).isEmpty();
    assertThat(cache.midHistory("AAPL")).isEmpty();
  }

  @Test
  void quoteTickPopulatesBidAskAndMid() {
    var cache = new MarketStateCache(CONFIG);
    cache.onTick(quote("AAPL", "100.00", "100.20"));
    var snap = cache.snapshot("AAPL").orElseThrow();
    assertThat(snap.bidPrice()).isEqualByComparingTo("100.00");
    assertThat(snap.askPrice()).isEqualByComparingTo("100.20");
    assertThat(snap.mid()).isEqualByComparingTo("100.10");
  }

  @Test
  void midRingBoundedByWindowSize() {
    var cache = new MarketStateCache(CONFIG);
    for (int i = 0; i < 10; i++) {
      cache.onTick(quote("AAPL", String.valueOf(100 + i), String.valueOf(100.2 + i)));
    }
    var history = cache.midHistory("AAPL").orElseThrow();
    // window size 5 → last 5 entries
    assertThat(history).hasSize(5);
    assertThat(history[0]).isCloseTo(105.1, within(0.001));
    assertThat(history[4]).isCloseTo(109.1, within(0.001));
  }

  @Test
  void tradeTickFallsBackToLastPriceForMid() {
    var cache = new MarketStateCache(CONFIG);
    cache.onTick(trade("AAPL", "200.00"));
    var snap = cache.snapshot("AAPL").orElseThrow();
    assertThat(snap.lastPrice()).isEqualByComparingTo("200.00");
    assertThat(snap.mid()).isEqualByComparingTo("200.00");
  }

  @Test
  void zeroPriceTickIgnored() {
    var cache = new MarketStateCache(CONFIG);
    cache.onTick(
        new MarketTick(
            "AAPL",
            Instant.now(),
            EventType.QUOTE,
            BigDecimal.ZERO,
            0L,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0L,
            0L,
            0L,
            DataSource.SIMULATED,
            false));
    assertThat(cache.snapshot("AAPL")).isPresent();
    assertThat(cache.snapshot("AAPL").orElseThrow().mid()).isEqualByComparingTo("0");
    assertThat(cache.midHistory("AAPL").orElseThrow()).isEmpty();
  }

  private static MarketTick quote(String symbol, String bid, String ask) {
    return new MarketTick(
        symbol,
        Instant.now(),
        EventType.QUOTE,
        BigDecimal.ZERO,
        0L,
        new BigDecimal(bid),
        new BigDecimal(ask),
        100L,
        100L,
        0L,
        DataSource.SIMULATED,
        false);
  }

  private static MarketTick trade(String symbol, String price) {
    return new MarketTick(
        symbol,
        Instant.now(),
        EventType.TRADE,
        new BigDecimal(price),
        100L,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0L,
        0L,
        0L,
        DataSource.SIMULATED,
        false);
  }
}
