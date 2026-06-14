package com.mariaalpha.executionengine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.adapter.VenueAdapterRegistry;
import com.mariaalpha.executionengine.handler.OrderTypeHandlerRegistry;
import com.mariaalpha.executionengine.iceberg.IcebergCoordinator;
import com.mariaalpha.executionengine.lifecycle.IllegalStateTransitionException;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.metrics.ExecutionMetrics;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.Fill;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.risk.DailyLossMonitor;
import com.mariaalpha.executionengine.risk.RiskCheckChain;
import com.mariaalpha.executionengine.router.SmartOrderRouter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderExecutionServiceZeroQuantityCancelTest {

  private OrderLifecycleManager lifecycleManager;
  private ExecutionMetrics metrics;
  private DailyLossMonitor dailyLossMonitor;
  private VenueAdapterRegistry venueAdapters;
  private OrderExecutionService service;
  private Order order;

  @BeforeEach
  void setUp() {
    lifecycleManager = mock(OrderLifecycleManager.class);
    metrics = mock(ExecutionMetrics.class);
    dailyLossMonitor = mock(DailyLossMonitor.class);
    venueAdapters = mock(VenueAdapterRegistry.class);
    when(venueAdapters.adapters()).thenReturn(List.of());

    service =
        new OrderExecutionService(
            mock(OrderTypeHandlerRegistry.class),
            mock(RiskCheckChain.class),
            mock(SmartOrderRouter.class),
            venueAdapters,
            lifecycleManager,
            mock(MarketStateTracker.class),
            dailyLossMonitor,
            metrics,
            mock(IcebergCoordinator.class),
            null,
            null);

    order =
        new Order(
            new OrderSignal(
                "AAPL",
                Side.BUY,
                100,
                OrderType.IOC,
                new BigDecimal("150.00"),
                null,
                "MANUAL",
                Instant.now()));
    order.setExchangeOrderId("SIM-abc");
    when(lifecycleManager.findByExchangeOrderId("SIM-abc")).thenReturn(order);
  }

  @Test
  void iocResidualCancelTransitionsToCancelledAndIncrementsCounter() {
    var report =
        new ExecutionReport(
            "SIM-abc", BigDecimal.ZERO, 0, 0, "SIMULATED", Instant.now(), "ioc-residual-cancel");

    service.onExecutionReport(report);

    verify(lifecycleManager)
        .transition(
            eq(order.getOrderId()), eq(OrderStatus.CANCELLED), isNull(), eq("ioc-residual-cancel"));
    verify(metrics).recordIocResidualCancelled("AAPL", "BUY");
    verify(metrics, never()).recordFokKilled(any(), any());
    verify(dailyLossMonitor, never()).onFill(any());
  }

  @Test
  void fokKilledTransitionsToCancelledAndIncrementsCounter() {
    var report =
        new ExecutionReport(
            "SIM-abc", BigDecimal.ZERO, 0, 0, "SIMULATED", Instant.now(), "fok-killed");

    service.onExecutionReport(report);

    verify(lifecycleManager)
        .transition(eq(order.getOrderId()), eq(OrderStatus.CANCELLED), isNull(), eq("fok-killed"));
    verify(metrics).recordFokKilled("AAPL", "BUY");
    verify(metrics, never()).recordIocResidualCancelled(any(), any());
  }

  @Test
  void genericExchangeCancelTransitionsToCancelledWithoutDedicatedCounter() {
    var report = new ExecutionReport("SIM-abc", BigDecimal.ZERO, 0, 0, "SIMULATED", Instant.now());

    service.onExecutionReport(report);

    verify(lifecycleManager)
        .transition(
            eq(order.getOrderId()), eq(OrderStatus.CANCELLED), isNull(), eq("exchange-cancel"));
    verify(metrics, never()).recordIocResidualCancelled(any(), any());
    verify(metrics, never()).recordFokKilled(any(), any());
  }

  @Test
  void illegalTransitionFromTerminalStateIsSwallowed() {
    var report =
        new ExecutionReport(
            "SIM-abc", BigDecimal.ZERO, 0, 0, "SIMULATED", Instant.now(), "ioc-residual-cancel");
    doThrow(
            new IllegalStateTransitionException(
                order.getOrderId(), OrderStatus.FILLED, OrderStatus.CANCELLED))
        .when(lifecycleManager)
        .transition(any(), eq(OrderStatus.CANCELLED), isNull(), any());

    service.onExecutionReport(report);

    verify(metrics, never()).recordIocResidualCancelled(any(), any());
    verify(metrics, never()).recordFokKilled(any(), any());
    verify(lifecycleManager, never())
        .transition(any(), eq(OrderStatus.PARTIALLY_FILLED), any(Fill.class), any());
  }
}
