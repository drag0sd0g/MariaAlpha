package com.mariaalpha.executionengine.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mariaalpha.executionengine.config.SimulatedConfig;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulatedExchangeAdapterTest {

  private SimulatedExchangeAdapter adapter;
  private MarketStateTracker marketStateTracker;
  private AtomicReference<ExecutionReport> lastReport;

  @BeforeEach
  void setUp() {
    var config = new SimulatedConfig(50, 2, 1.0, "SIMULATED");
    marketStateTracker = new MarketStateTracker();
    marketStateTracker.update(
        new MarketState(
            "AAPL",
            new BigDecimal("149.50"),
            new BigDecimal("150.50"),
            new BigDecimal("150.00"),
            Instant.now()));
    adapter = new SimulatedExchangeAdapter(config, marketStateTracker);
    lastReport = new AtomicReference<>();
    adapter.onExecutionReport(lastReport::set);
    adapter.start();
  }

  @AfterEach
  void tearDown() {
    adapter.shutdown();
  }

  @Test
  void marketOrderFillsImmediately() {
    var order = createOrder(OrderType.MARKET, null, null, Side.BUY);
    var instruction = new ExecutionInstruction(order, "day", null);
    var ack = adapter.submitOrder(instruction);

    assertThat(ack.accepted()).isTrue();
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(lastReport.get()).isNotNull());
    assertThat(lastReport.get().fillQuantity()).isEqualTo(100);
    assertThat(lastReport.get().remainingQuantity()).isEqualTo(0);
  }

  @Test
  void limitOrderFillsWhenPriceMet() {
    var order = createOrder(OrderType.LIMIT, new BigDecimal("151.00"), null, Side.BUY);
    var instruction = new ExecutionInstruction(order, "day", new BigDecimal("151.00"));
    var ack = adapter.submitOrder(instruction);

    assertThat(ack.accepted()).isTrue();
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(lastReport.get()).isNotNull());
  }

  @Test
  void limitOrderPendsWhenPriceNotMet() throws InterruptedException {
    var order = createOrder(OrderType.LIMIT, new BigDecimal("148.00"), null, Side.BUY);
    var instruction = new ExecutionInstruction(order, "day", new BigDecimal("148.00"));
    adapter.submitOrder(instruction);

    Thread.sleep(200);
    assertThat(lastReport.get()).isNull();
  }

  @Test
  void stopOrderWaitsForTrigger() throws InterruptedException {
    var order = createOrder(OrderType.STOP, null, new BigDecimal("152.00"), Side.BUY);
    var instruction = new ExecutionInstruction(order, "day", null);
    adapter.submitOrder(instruction);

    Thread.sleep(200);
    assertThat(lastReport.get()).isNull();

    // Trigger with price crossing stop
    adapter.onMarketUpdate(
        new MarketState(
            "AAPL",
            new BigDecimal("151.50"),
            new BigDecimal("152.50"),
            new BigDecimal("152.50"),
            Instant.now()));

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(lastReport.get()).isNotNull());
  }

  @Test
  void cancelRemovesPendingOrder() {
    var order = createOrder(OrderType.LIMIT, new BigDecimal("140.00"), null, Side.BUY);
    var instruction = new ExecutionInstruction(order, "day", new BigDecimal("140.00"));
    var ack = adapter.submitOrder(instruction);

    var cancelAck = adapter.cancelOrder(ack.exchangeOrderId());
    assertThat(cancelAck.accepted()).isTrue();
    assertThat(cancelAck.reason()).isEqualTo("cancelled");
  }

  @Test
  void slippageApplied() {
    var order = createOrder(OrderType.MARKET, null, null, Side.BUY);
    var instruction = new ExecutionInstruction(order, "day", null);
    adapter.submitOrder(instruction);

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(lastReport.get()).isNotNull());
    // Ask = 150.50, slippage = 2bps = 0.0301 → fill at ~150.53
    assertThat(lastReport.get().fillPrice()).isGreaterThan(new BigDecimal("150.50"));
  }

  @Test
  void isHealthyReturnsTrue() {
    assertThat(adapter.isHealthy()).isTrue();
  }

  private Order createOrder(
      OrderType type, BigDecimal limitPrice, BigDecimal stopPrice, Side side) {
    var signal =
        new OrderSignal("AAPL", side, 100, type, limitPrice, stopPrice, "VWAP", Instant.now());
    return new Order(signal);
  }
}
