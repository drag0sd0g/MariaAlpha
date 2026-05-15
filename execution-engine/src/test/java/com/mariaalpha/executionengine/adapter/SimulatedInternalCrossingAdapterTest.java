package com.mariaalpha.executionengine.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mariaalpha.executionengine.config.InternalCrossingConfig;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.router.VenueType;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulatedInternalCrossingAdapterTest {

  private MarketStateTracker tracker;
  private SimulatedInternalCrossingAdapter adapter;
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
    reports = new CopyOnWriteArrayList<>();
    adapter = new SimulatedInternalCrossingAdapter(seed(42, 1.0, 0.0), tracker);
    adapter.onExecutionReport(reports::add);
  }

  @AfterEach
  void tearDown() {
    adapter.shutdown();
  }

  @Test
  void venueAndTypeMatchConfig() {
    assertThat(adapter.venueName()).isEqualTo("INTERNAL_CROSS");
    assertThat(adapter.venueType()).isEqualTo(VenueType.INTERNAL);
  }

  @Test
  void crossOnSubmitFillsAtMidpoint() {
    adapter.start();
    adapter.submitOrder(instruction(100));
    await().atMost(Duration.ofSeconds(2)).until(() -> !reports.isEmpty());
    assertThat(reports.get(0).venue()).isEqualTo("INTERNAL_CROSS");
    assertThat(reports.get(0).fillPrice()).isEqualByComparingTo("178.52");
    assertThat(reports.get(0).fillQuantity()).isEqualTo(100);
  }

  @Test
  void fallsThroughToPendingWhenUnlucky() {
    // crossProb=0 → never crosses on submit; matchProb=0 → never matches on tick; pending grows.
    adapter = new SimulatedInternalCrossingAdapter(seed(42, 0.0, 0.0), tracker);
    adapter.onExecutionReport(reports::add);
    adapter.start();
    adapter.submitOrder(instruction(100));
    assertThat(adapter.pendingSize()).isEqualTo(1);
    assertThat(reports).isEmpty();
  }

  @Test
  void periodicMatchingFillsPending() {
    adapter = new SimulatedInternalCrossingAdapter(seed(42, 0.0, 1.0), tracker);
    adapter.onExecutionReport(reports::add);
    adapter.start();
    adapter.submitOrder(instruction(100));
    adapter.matchTick();
    assertThat(reports).hasSize(1);
    assertThat(adapter.pendingSize()).isZero();
  }

  @Test
  void cancelRemovesPending() {
    adapter = new SimulatedInternalCrossingAdapter(seed(42, 0.0, 0.0), tracker);
    adapter.onExecutionReport(reports::add);
    var ack = adapter.submitOrder(instruction(100));
    assertThat(adapter.pendingSize()).isEqualTo(1);
    var cancel = adapter.cancelOrder(ack.exchangeOrderId());
    assertThat(cancel.accepted()).isTrue();
    assertThat(adapter.pendingSize()).isZero();
  }

  @Test
  void healthReflectsScheduler() {
    assertThat(adapter.isHealthy()).isFalse();
    adapter.start();
    assertThat(adapter.isHealthy()).isTrue();
    adapter.shutdown();
    assertThat(adapter.isHealthy()).isFalse();
  }

  @Test
  void capacityCheck() {
    adapter = new SimulatedInternalCrossingAdapter(seedMax(42, 0.0, 0.0, 1), tracker);
    adapter.onExecutionReport(reports::add);
    adapter.start();
    assertThat(adapter.submitOrder(instruction(100)).accepted()).isTrue();
    var rejected = adapter.submitOrder(instruction(100));
    assertThat(rejected.accepted()).isFalse();
    assertThat(rejected.reason()).contains("capacity");
  }

  private static InternalCrossingConfig seed(long seed, double crossProb, double matchProb) {
    return new InternalCrossingConfig("INTERNAL_CROSS", crossProb, 50, matchProb, 1, 1000, seed);
  }

  private static InternalCrossingConfig seedMax(
      long seed, double crossProb, double matchProb, int max) {
    return new InternalCrossingConfig("INTERNAL_CROSS", crossProb, 50, matchProb, 1, max, seed);
  }

  private ExecutionInstruction instruction(int qty) {
    var order =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, qty, OrderType.MARKET, null, null, "T", Instant.now()));
    return new ExecutionInstruction(order, "day", null);
  }
}
