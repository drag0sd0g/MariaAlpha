package com.mariaalpha.executionengine.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.config.DarkPoolConfig;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import com.mariaalpha.executionengine.router.VenueType;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulatedDarkPoolAdapterTest {

  private MarketStateTracker tracker;
  private SimulatedDarkPoolAdapter adapter;
  private List<ExecutionReport> reports;

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
    reports = new ArrayList<>();
    adapter = adapterWith(seedConfig(42, 0.8));
    adapter.onExecutionReport(reports::add);
  }

  @AfterEach
  void tearDown() {
    adapter.shutdown();
  }

  @Test
  void venueAndTypeMatchConfig() {
    assertThat(adapter.venueName()).isEqualTo("DARK_POOL_A");
    assertThat(adapter.venueType()).isEqualTo(VenueType.DARK);
  }

  @Test
  void submitEnqueues() {
    var ack = adapter.submitOrder(instruction(100));
    assertThat(ack.accepted()).isTrue();
    assertThat(ack.exchangeOrderId()).startsWith("DARK-");
    assertThat(adapter.pendingSize()).isEqualTo(1);
  }

  @Test
  void seededMatchTickProducesDeterministicFills() {
    adapter.start();
    adapter.submitOrder(instruction(100));
    adapter.matchTick();
    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).venue()).isEqualTo("DARK_POOL_A");
    assertThat(reports.get(0).fillPrice()).isEqualByComparingTo("178.52");
  }

  @Test
  void partialFillRatioRespected() {
    adapter = adapterWith(seedConfigWithRatio(42, 1.0, 0.5));
    adapter.onExecutionReport(reports::add);
    adapter.start();
    adapter.submitOrder(instruction(100));
    adapter.matchTick();
    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).fillQuantity()).isEqualTo(50);
    assertThat(reports.get(0).remainingQuantity()).isEqualTo(50);
  }

  @Test
  void minSpreadFilterSkipsTightMarkets() {
    tracker.update(
        new MarketState(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.5001"),
            new BigDecimal("178.50"),
            Instant.now()));
    adapter = adapterWith(seedConfig(42, 1.0));
    adapter.onExecutionReport(reports::add);
    adapter.start();
    adapter.submitOrder(instruction(100));
    adapter.matchTick();
    assertThat(reports).isEmpty();
  }

  @Test
  void cancelRemovesPending() {
    var ack = adapter.submitOrder(instruction(100));
    var cancelAck = adapter.cancelOrder(ack.exchangeOrderId());
    assertThat(cancelAck.accepted()).isTrue();
    assertThat(adapter.pendingSize()).isZero();
  }

  @Test
  void maxPendingRejects() {
    adapter = adapterWith(seedConfigMaxPending(42, 1.0, 2));
    adapter.onExecutionReport(reports::add);
    assertThat(adapter.submitOrder(instruction(100)).accepted()).isTrue();
    assertThat(adapter.submitOrder(instruction(100)).accepted()).isTrue();
    var rejected = adapter.submitOrder(instruction(100));
    assertThat(rejected.accepted()).isFalse();
    assertThat(rejected.reason()).contains("capacity");
  }

  @Test
  void nullMarketStateNoFill() {
    tracker = new MarketStateTracker();
    adapter = adapterWith(seedConfig(42, 1.0), tracker);
    adapter.onExecutionReport(reports::add);
    adapter.start();
    adapter.submitOrder(instruction(100));
    adapter.matchTick();
    assertThat(reports).isEmpty();
  }

  private SimulatedDarkPoolAdapter adapterWith(DarkPoolConfig config) {
    return new SimulatedDarkPoolAdapter(config, tracker);
  }

  private SimulatedDarkPoolAdapter adapterWith(DarkPoolConfig config, MarketStateTracker t) {
    return new SimulatedDarkPoolAdapter(config, t);
  }

  private static DarkPoolConfig seedConfig(long seed, double matchProb) {
    return new DarkPoolConfig("DARK_POOL_A", 100, matchProb, 1, 30, 1.0, 1000, seed);
  }

  private static DarkPoolConfig seedConfigWithRatio(long seed, double matchProb, double partial) {
    return new DarkPoolConfig("DARK_POOL_A", 100, matchProb, 1, 30, partial, 1000, seed);
  }

  private static DarkPoolConfig seedConfigMaxPending(long seed, double matchProb, int max) {
    return new DarkPoolConfig("DARK_POOL_A", 100, matchProb, 1, 30, 1.0, max, seed);
  }

  private ExecutionInstruction instruction(int qty) {
    var order =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, qty, OrderType.MARKET, null, null, "T", Instant.now()));
    return new ExecutionInstruction(order, TimeInForce.DAY, null);
  }
}
