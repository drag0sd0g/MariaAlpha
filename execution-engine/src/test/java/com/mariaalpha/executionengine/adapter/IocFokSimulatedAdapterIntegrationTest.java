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
import com.mariaalpha.executionengine.model.TimeInForce;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IocFokSimulatedAdapterIntegrationTest {

  private SimulatedExchangeAdapter adapter;
  private MarketStateTracker marketStateTracker;
  private AtomicReference<ExecutionReport> lastReport;

  @BeforeEach
  void setUp() {
    var config = new SimulatedConfig(50, 0, 1.0, "SIMULATED");
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
  void iocMarketableBuyFillsImmediately() {
    var instruction = iocInstruction(Side.BUY, 100, new BigDecimal("151.00"));

    var ack = adapter.submitOrder(instruction);

    assertThat(ack.accepted()).isTrue();
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(lastReport.get()).isNotNull());
    var report = lastReport.get();
    assertThat(report.fillQuantity()).isEqualTo(100);
    assertThat(report.remainingQuantity()).isEqualTo(0);
    assertThat(report.reason()).isNull();
  }

  @Test
  void iocNonMarketableBuyEmitsResidualCancel() {
    var instruction = iocInstruction(Side.BUY, 100, new BigDecimal("149.00"));

    var ack = adapter.submitOrder(instruction);

    assertThat(ack.accepted()).isTrue();
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(lastReport.get()).isNotNull());
    var report = lastReport.get();
    assertThat(report.fillQuantity()).isEqualTo(0);
    assertThat(report.remainingQuantity()).isEqualTo(0);
    assertThat(report.reason()).isEqualTo("ioc-residual-cancel");
  }

  @Test
  void iocNonMarketableSellEmitsResidualCancel() {
    var instruction = iocInstruction(Side.SELL, 100, new BigDecimal("151.00"));

    adapter.submitOrder(instruction);

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(lastReport.get()).isNotNull());
    var report = lastReport.get();
    assertThat(report.fillQuantity()).isEqualTo(0);
    assertThat(report.reason()).isEqualTo("ioc-residual-cancel");
  }

  @Test
  void fokMarketableBuyFillsImmediately() {
    var instruction = fokInstruction(Side.BUY, 100, new BigDecimal("151.00"));

    var ack = adapter.submitOrder(instruction);

    assertThat(ack.accepted()).isTrue();
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(lastReport.get()).isNotNull());
    var report = lastReport.get();
    assertThat(report.fillQuantity()).isEqualTo(100);
    assertThat(report.remainingQuantity()).isEqualTo(0);
    assertThat(report.reason()).isNull();
  }

  @Test
  void fokNonMarketableEmitsKillReport() {
    var instruction = fokInstruction(Side.BUY, 100, new BigDecimal("149.00"));

    adapter.submitOrder(instruction);

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(lastReport.get()).isNotNull());
    var report = lastReport.get();
    assertThat(report.fillQuantity()).isEqualTo(0);
    assertThat(report.remainingQuantity()).isEqualTo(0);
    assertThat(report.reason()).isEqualTo("fok-killed");
  }

  private ExecutionInstruction iocInstruction(Side side, int qty, BigDecimal limitPrice) {
    return instruction(OrderType.IOC, TimeInForce.IOC, side, qty, limitPrice);
  }

  private ExecutionInstruction fokInstruction(Side side, int qty, BigDecimal limitPrice) {
    return instruction(OrderType.FOK, TimeInForce.FOK, side, qty, limitPrice);
  }

  private ExecutionInstruction instruction(
      OrderType type, TimeInForce tif, Side side, int qty, BigDecimal limitPrice) {
    var order =
        new Order(
            new OrderSignal("AAPL", side, qty, type, limitPrice, null, "MANUAL", Instant.now()));
    return new ExecutionInstruction(order, tif, limitPrice);
  }
}
