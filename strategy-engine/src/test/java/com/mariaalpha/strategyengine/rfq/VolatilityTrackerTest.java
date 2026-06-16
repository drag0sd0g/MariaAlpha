package com.mariaalpha.strategyengine.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VolatilityTrackerTest {

  private static final RfqPricingConfig CONFIG =
      new RfqPricingConfig(4.0, 1.0, 1_000_000.0, 30.0, 0.5, 0.3, 10_000L, "http://x", 500L, 30);

  @Test
  void returnsZeroWithoutEnoughSamples() {
    var cache = new MarketStateCache(CONFIG);
    var tracker = new VolatilityTracker(cache);
    assertThat(tracker.realizedVolBps("AAPL")).isZero();
    cache.onTick(quote("AAPL", "100.00", "100.10"));
    assertThat(tracker.realizedVolBps("AAPL")).isZero();
  }

  @Test
  void flatPriceSeriesYieldsZeroVol() {
    var cache = new MarketStateCache(CONFIG);
    var tracker = new VolatilityTracker(cache);
    for (int i = 0; i < 10; i++) {
      cache.onTick(quote("AAPL", "100.00", "100.10"));
    }
    assertThat(tracker.realizedVolBps("AAPL")).isCloseTo(0.0, within(1e-6));
  }

  @Test
  void volatilityProportionalToDispersion() {
    var cache = new MarketStateCache(CONFIG);
    var tracker = new VolatilityTracker(cache);

    cache.onTick(quote("AAPL", "100.00", "100.20"));
    cache.onTick(quote("AAPL", "100.50", "100.70"));
    cache.onTick(quote("AAPL", "100.00", "100.20"));

    double vol = tracker.realizedVolBps("AAPL");
    assertThat(vol).isCloseTo(70.4, within(0.5));
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
}
