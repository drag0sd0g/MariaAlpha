package com.mariaalpha.marketdatagateway.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.EventType;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class OrderBookManagerTest {

  private final Sinks.Many<MarketTick> tickSink = Sinks.many().multicast().onBackpressureBuffer();
  private OrderBookManager manager;

  @BeforeEach
  void setUp() {
    var adapter = mock(MarketDataAdapter.class);
    when(adapter.streamTicks()).thenReturn(tickSink.asFlux());
    manager = new OrderBookManager(adapter);
    manager.start();
  }

  @AfterEach
  void tearDown() {
    manager.stop();
  }

  @Test
  void snapshotForUnknownSymbolReturnsEmpty() {
    var snapshot = manager.getSnapshot("UNKNOWN");

    assertThat(snapshot.symbol()).isEqualTo("UNKNOWN");
    assertThat(snapshot.lastPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(snapshot.bidPrice()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void tradeTickUpdatesBook() {
    tickSink.tryEmitNext(tradeTick("AAPL", "178.52", 100, 1000000));

    var snapshot = manager.getSnapshot("AAPL");

    assertThat(snapshot.lastPrice()).isEqualByComparingTo(new BigDecimal("178.52"));
    assertThat(snapshot.cumulativeVolume()).isEqualTo(1000000L);
  }

  @Test
  void quoteTickUpdatesBook() {
    tickSink.tryEmitNext(quoteTick("AAPL", "178.50", "178.54", 200, 150));

    var snapshot = manager.getSnapshot("AAPL");

    assertThat(snapshot.bidPrice()).isEqualByComparingTo(new BigDecimal("178.50"));
    assertThat(snapshot.askPrice()).isEqualByComparingTo(new BigDecimal("178.54"));
    assertThat(snapshot.bidSize()).isEqualTo(200L);
    assertThat(snapshot.askSize()).isEqualTo(150L);
  }

  @Test
  void multipleSymbolsMaintainSeparateBooks() {
    tickSink.tryEmitNext(tradeTick("AAPL", "178.52", 100, 1000000));
    tickSink.tryEmitNext(tradeTick("MSFT", "415.20", 50, 500000));

    assertThat(manager.getSnapshot("AAPL").lastPrice())
        .isEqualByComparingTo(new BigDecimal("178.52"));
    assertThat(manager.getSnapshot("MSFT").lastPrice())
        .isEqualByComparingTo(new BigDecimal("415.20"));
  }

  @Test
  void streamSnapshotsEmitsOnUpdate() {
    var flux = manager.streamSnapshots(Set.of("AAPL"));

    StepVerifier.create(flux.take(2))
        .then(() -> tickSink.tryEmitNext(tradeTick("AAPL", "178.52", 100, 1000000)))
        .then(() -> tickSink.tryEmitNext(quoteTick("AAPL", "178.50", "178.54", 200, 150)))
        .assertNext(
            entry -> assertThat(entry.lastPrice()).isEqualByComparingTo(new BigDecimal("178.52")))
        .assertNext(
            entry -> assertThat(entry.bidPrice()).isEqualByComparingTo(new BigDecimal("178.50")))
        .verifyComplete();
  }

  @Test
  void streamSnapshotsFiltersBySymbol() {
    var flux = manager.streamSnapshots(Set.of("AAPL"));

    StepVerifier.create(flux.take(1))
        .then(() -> tickSink.tryEmitNext(tradeTick("MSFT", "415.20", 50, 500000)))
        .then(() -> tickSink.tryEmitNext(tradeTick("AAPL", "178.52", 100, 1000000)))
        .assertNext(entry -> assertThat(entry.symbol()).isEqualTo("AAPL"))
        .verifyComplete();
  }

  @Test
  void concurrentUpdatesForSameSymbolAreConsistent() throws Exception {
    int threadCount = 8;
    int ticksPerThread = 1000;
    var latch = new CountDownLatch(threadCount);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              for (int i = 0; i < ticksPerThread; i++) {
                long volume = (long) threadId * ticksPerThread + i;
                manager.onTick(tradeTick("AAPL", String.valueOf(100 + i), 1, volume));
              }
            } finally {
              latch.countDown();
            }
          });
    }

    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    var snapshot = manager.getSnapshot("AAPL");
    // The book must have a valid state — lastPrice must be one of the submitted prices
    assertThat(snapshot.lastPrice().doubleValue()).isBetween(100.0, 100.0 + ticksPerThread);
    assertThat(snapshot.symbol()).isEqualTo("AAPL");
  }

  @Test
  void concurrentUpdatesForDifferentSymbolsAreIndependent() throws Exception {
    int symbolCount = 10;
    int ticksPerSymbol = 500;
    var latch = new CountDownLatch(symbolCount);
    ExecutorService executor = Executors.newFixedThreadPool(symbolCount);
    List<String> symbols = new ArrayList<>();

    for (int s = 0; s < symbolCount; s++) {
      String symbol = "SYM" + s;
      symbols.add(symbol);
      final BigDecimal expectedPrice = BigDecimal.valueOf(100 + s);
      executor.submit(
          () -> {
            try {
              for (int i = 0; i < ticksPerSymbol; i++) {
                manager.onTick(tradeTick(symbol, expectedPrice.toPlainString(), 1, i + 1));
              }
            } finally {
              latch.countDown();
            }
          });
    }

    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    for (int s = 0; s < symbolCount; s++) {
      var snapshot = manager.getSnapshot(symbols.get(s));
      assertThat(snapshot.lastPrice()).isEqualByComparingTo(BigDecimal.valueOf(100 + s));
      assertThat(snapshot.cumulativeVolume()).isEqualTo(ticksPerSymbol);
    }
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
