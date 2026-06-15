package com.mariaalpha.executionengine.crossing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InternalCrossingEngineTest {

  private MarketStateTracker tracker;
  private InternalCrossingEngine engine;
  private List<MidpointCross> crosses;

  @BeforeEach
  void setUp() {
    tracker = new MarketStateTracker();
    tracker.update(
        new MarketState(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            new BigDecimal("178.52"),
            Instant.now()));
    engine = new InternalCrossingEngine(tracker);
    crosses = new ArrayList<>();
    engine.addCrossListener(crosses::add);
  }

  @Test
  void incomingBuyCrossesRestingSellAtMidpoint() {
    var sellId = engine.submit(market("AAPL", Side.SELL, 100));
    var buyId = engine.submit(market("AAPL", Side.BUY, 100));

    assertThat(crosses).hasSize(1);
    var cross = crosses.get(0);
    assertThat(cross.symbol()).isEqualTo("AAPL");
    assertThat(cross.quantity()).isEqualTo(100);
    assertThat(cross.midpoint()).isEqualByComparingTo("178.52");
    assertThat(cross.synthetic()).isFalse();
    assertThat(cross.spreadBps()).isPositive();
    assertThat(engine.isResting(sellId)).isFalse();
    assertThat(engine.isResting(buyId)).isFalse();
    assertThat(engine.stats().restingOrdersBuy()).isZero();
    assertThat(engine.stats().restingOrdersSell()).isZero();
  }

  @Test
  void partialMatchLeavesRemainderResting() {
    var sellId = engine.submit(market("AAPL", Side.SELL, 100));
    var buyId = engine.submit(market("AAPL", Side.BUY, 30));

    assertThat(crosses).hasSize(1);
    assertThat(crosses.get(0).quantity()).isEqualTo(30);
    assertThat(engine.isResting(sellId)).isTrue();
    assertThat(engine.remainingFor(sellId)).isEqualTo(70);
    assertThat(engine.isResting(buyId)).isFalse();
  }

  @Test
  void fifoTimePriorityAcrossSeveralRestingOrders() {
    var sell1 = engine.submit(market("AAPL", Side.SELL, 40));
    sleepMillis(2);
    var sell2 = engine.submit(market("AAPL", Side.SELL, 40));
    engine.submit(market("AAPL", Side.BUY, 60));

    assertThat(crosses).hasSize(2);
    assertThat(crosses.get(0).quantity()).isEqualTo(40);
    assertThat(crosses.get(0).counterpartyExchangeOrderId()).isEqualTo(sell1);
    assertThat(crosses.get(1).quantity()).isEqualTo(20);
    assertThat(crosses.get(1).counterpartyExchangeOrderId()).isEqualTo(sell2);
    assertThat(engine.remainingFor(sell2)).isEqualTo(20);
  }

  @Test
  void noCrossIfOnlyOneSideOfInterestExists() {
    engine.submit(market("AAPL", Side.BUY, 100));
    assertThat(crosses).isEmpty();
    assertThat(engine.stats().restingOrdersBuy()).isEqualTo(1);
  }

  @Test
  void buyLimitBelowMidpointDoesNotCross() {
    engine.submit(market("AAPL", Side.SELL, 100));
    engine.submit(limit("AAPL", Side.BUY, 100, new BigDecimal("178.50")));
    assertThat(crosses).isEmpty();
    assertThat(engine.stats().restingOrdersBuy()).isEqualTo(1);
    assertThat(engine.stats().restingOrdersSell()).isEqualTo(1);
  }

  @Test
  void sellLimitAboveMidpointDoesNotCross() {
    engine.submit(limit("AAPL", Side.SELL, 100, new BigDecimal("178.55")));
    engine.submit(market("AAPL", Side.BUY, 100));
    assertThat(crosses).isEmpty();
  }

  @Test
  void sweepAfterFavourableNbboShiftCrossesPreviouslyStalledOrders() {
    engine.submit(limit("AAPL", Side.SELL, 100, new BigDecimal("178.55")));
    engine.submit(limit("AAPL", Side.BUY, 100, new BigDecimal("178.55")));
    assertThat(crosses).isEmpty();
    tracker.update(
        new MarketState(
            "AAPL",
            new BigDecimal("178.54"),
            new BigDecimal("178.56"),
            new BigDecimal("178.55"),
            Instant.now()));
    var swept = engine.sweep();
    assertThat(swept).hasSize(1);
    assertThat(swept.get(0).midpoint()).isEqualByComparingTo("178.55");
    assertThat(crosses).hasSize(1);
  }

  @Test
  void cancelRemovesRestingOrder() {
    var sellId = engine.submit(market("AAPL", Side.SELL, 100));
    assertThat(engine.cancel(sellId)).isTrue();
    assertThat(engine.cancel(sellId)).isFalse();
    engine.submit(market("AAPL", Side.BUY, 100));
    assertThat(crosses).isEmpty();
  }

  @Test
  void noCrossIfMarketDataMissing() {
    tracker.update(new MarketState("MSFT", null, null, null, Instant.now()));
    engine.submit(market("MSFT", Side.SELL, 50));
    engine.submit(market("MSFT", Side.BUY, 50));
    assertThat(crosses).isEmpty();
    assertThat(engine.stats().restingOrdersBuy()).isEqualTo(1);
    assertThat(engine.stats().restingOrdersSell()).isEqualTo(1);
  }

  @Test
  void noCrossIfMarketDataCrossedOrInverted() {
    tracker.update(
        new MarketState(
            "MSFT",
            new BigDecimal("100.10"),
            new BigDecimal("100.00"),
            new BigDecimal("100.05"),
            Instant.now()));
    engine.submit(market("MSFT", Side.SELL, 50));
    engine.submit(market("MSFT", Side.BUY, 50));
    assertThat(crosses).isEmpty();
  }

  @Test
  void syntheticCounterpartyFillsRestingOrder() {
    var id = engine.submit(market("AAPL", Side.BUY, 100));
    var produced = engine.synthesizeCounterparty(id);
    assertThat(produced).isPresent();
    assertThat(produced.get().synthetic()).isTrue();
    assertThat(produced.get().counterpartyExchangeOrderId()).isNull();
    assertThat(crosses).hasSize(1);
    assertThat(engine.isResting(id)).isFalse();
  }

  @Test
  void syntheticCounterpartyNoOpIfOrderAlreadyFilled() {
    var sellId = engine.submit(market("AAPL", Side.SELL, 100));
    engine.submit(market("AAPL", Side.BUY, 100));
    assertThat(crosses).hasSize(1);
    var synth = engine.synthesizeCounterparty(sellId);
    assertThat(synth).isEmpty();
  }

  @Test
  void statsTrackInternalAndSyntheticCrossesSeparately() {
    engine.submit(market("AAPL", Side.SELL, 100));
    engine.submit(market("AAPL", Side.BUY, 100));
    var solo = engine.submit(market("AAPL", Side.BUY, 50));
    engine.synthesizeCounterparty(solo);

    var stats = engine.stats();
    assertThat(stats.crossesTotal()).isEqualTo(2);
    assertThat(stats.internalCrossesTotal()).isEqualTo(1);
    assertThat(stats.syntheticCrossesTotal()).isEqualTo(1);
    assertThat(stats.sharesCrossedTotal()).isEqualTo(150);
    assertThat(stats.spreadCapturedNotional()).isPositive();
  }

  @Test
  void bookSnapshotReturnsLiveDepth() {
    engine.submit(market("AAPL", Side.BUY, 100));
    engine.submit(market("AAPL", Side.BUY, 200));
    engine.submit(market("AAPL", Side.SELL, 50));

    var snap = engine.bookSnapshot();
    assertThat(snap).containsKey("AAPL");
    var aapl = snap.get("AAPL");
    assertThat(aapl.buyQty()).isEqualTo(250);
    assertThat(aapl.buyOrders()).isEqualTo(2);
    assertThat(aapl.sellQty()).isZero();
    assertThat(aapl.sellOrders()).isZero();
  }

  @Test
  void recentCrossesReturnedMostRecentFirst() {
    engine.submit(market("AAPL", Side.SELL, 100));
    engine.submit(market("AAPL", Side.BUY, 30));
    engine.submit(market("AAPL", Side.BUY, 70));

    var recent = engine.recentCrosses();
    assertThat(recent).hasSize(2);
    assertThat(recent.get(0).timestamp()).isAfterOrEqualTo(recent.get(1).timestamp());
  }

  @Test
  void concurrentSubmitsProduceConsistentBookState() throws Exception {
    int per = 50;
    var pool = Executors.newFixedThreadPool(4);
    var ready = new CountDownLatch(1);
    try {
      var tasks = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < per; i++) {
        tasks.add(
            pool.submit(
                () -> {
                  await(ready);
                  engine.submit(market("AAPL", Side.BUY, 10));
                }));
        tasks.add(
            pool.submit(
                () -> {
                  await(ready);
                  engine.submit(market("AAPL", Side.SELL, 10));
                }));
      }
      ready.countDown();
      for (var t : tasks) {
        t.get(5, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(crosses.stream().mapToInt(MidpointCross::quantity).sum()).isEqualTo(per * 10);
    assertThat(engine.totalResting()).isZero();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleepMillis(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Order market(String symbol, Side side, int qty) {
    return new Order(
        new OrderSignal(symbol, side, qty, OrderType.MARKET, null, null, "T", Instant.now()));
  }

  private static Order limit(String symbol, Side side, int qty, BigDecimal limit) {
    return new Order(
        new OrderSignal(symbol, side, qty, OrderType.LIMIT, limit, null, "T", Instant.now()));
  }

  private static ExecutorService anyPool() {
    return Executors.newCachedThreadPool();
  }
}
