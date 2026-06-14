package com.mariaalpha.executionengine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.adapter.VenueAdapter;
import com.mariaalpha.executionengine.adapter.VenueAdapterRegistry;
import com.mariaalpha.executionengine.handler.MarketOrderHandler;
import com.mariaalpha.executionengine.handler.OrderTypeHandlerRegistry;
import com.mariaalpha.executionengine.iceberg.IcebergCoordinator;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.metrics.ExecutionMetrics;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.Fill;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderAck;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import com.mariaalpha.executionengine.model.RoutingDecision;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.risk.DailyLossMonitor;
import com.mariaalpha.executionengine.risk.RiskCheckChain;
import com.mariaalpha.executionengine.router.SmartOrderRouter;
import com.mariaalpha.executionengine.router.VenueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderExecutionServiceTest {

  private OrderTypeHandlerRegistry handlerRegistry;
  private RiskCheckChain riskCheckChain;
  private SmartOrderRouter router;
  private VenueAdapterRegistry venueAdapters;
  private VenueAdapter primaryVenueAdapter;
  private OrderLifecycleManager lifecycleManager;
  private MarketStateTracker marketStateTracker;
  private DailyLossMonitor dailyLossMonitor;
  private ExecutionMetrics metrics;
  private IcebergCoordinator icebergCoordinator;
  private OrderExecutionService service;

  @BeforeEach
  void setUp() {
    handlerRegistry = mock(OrderTypeHandlerRegistry.class);
    riskCheckChain = mock(RiskCheckChain.class);
    router = mock(SmartOrderRouter.class);
    venueAdapters = mock(VenueAdapterRegistry.class);
    primaryVenueAdapter = mock(VenueAdapter.class);
    lifecycleManager = mock(OrderLifecycleManager.class);
    marketStateTracker = mock(MarketStateTracker.class);
    dailyLossMonitor = mock(DailyLossMonitor.class);
    metrics = mock(ExecutionMetrics.class);
    icebergCoordinator = mock(IcebergCoordinator.class);

    when(primaryVenueAdapter.venueName()).thenReturn("PRIMARY");
    when(primaryVenueAdapter.venueType()).thenReturn(VenueType.LIT);
    when(venueAdapters.get("PRIMARY")).thenReturn(Optional.of(primaryVenueAdapter));
    when(venueAdapters.adapters()).thenReturn(java.util.List.of(primaryVenueAdapter));

    when(dailyLossMonitor.isTradingHalted()).thenReturn(false);
    when(router.route(any()))
        .thenAnswer(
            inv -> {
              Order o = inv.getArgument(0);
              o.setVenue("PRIMARY");
              return RoutingDecision.legacy(
                  o.getOrderId(), "PRIMARY", "DirectRouter", Instant.now());
            });

    service =
        new OrderExecutionService(
            handlerRegistry,
            riskCheckChain,
            router,
            venueAdapters,
            lifecycleManager,
            marketStateTracker,
            dailyLossMonitor,
            metrics,
            icebergCoordinator,
            null,
            null);
    service.registerCallbacks();
  }

  @Test
  void happyPathMarketOrder() {
    var handler = new MarketOrderHandler();
    when(handlerRegistry.getHandler(OrderType.MARKET)).thenReturn(Optional.of(handler));
    when(marketStateTracker.getMarketState("AAPL"))
        .thenReturn(
            new MarketState(
                "AAPL",
                new BigDecimal("149"),
                new BigDecimal("151"),
                new BigDecimal("150"),
                Instant.now()));
    when(riskCheckChain.evaluate(any())).thenReturn(RiskCheckResult.pass("ALL"));
    when(primaryVenueAdapter.submitOrder(any()))
        .thenReturn(new OrderAck("order-1", "SIM-abc", true, ""));

    var signal =
        new OrderSignal("AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now());
    service.executeSignal(signal);

    verify(lifecycleManager).registerOrder(any());
    verify(lifecycleManager).transition(any(), eq(OrderStatus.SUBMITTED), isNull(), isNull());
    verify(primaryVenueAdapter).submitOrder(any());
  }

  @Test
  void rejectsWhenTradingHalted() {
    when(dailyLossMonitor.isTradingHalted()).thenReturn(true);

    var signal =
        new OrderSignal("AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now());
    service.executeSignal(signal);

    verify(primaryVenueAdapter, never()).submitOrder(any());
    verify(metrics).recordRejection("TradingHalted");
    // The rejection must be visible downstream: the order is registered and transitioned to
    // REJECTED so order-manager / the UI see the drop instead of a silent no-op.
    verify(lifecycleManager).registerOrder(any());
    verify(lifecycleManager)
        .transition(any(), eq(OrderStatus.REJECTED), isNull(), eq("Trading halted"));
  }

  @Test
  void rejectsUnsupportedOrderType() {
    when(handlerRegistry.getHandler(any())).thenReturn(Optional.empty());

    var signal =
        new OrderSignal("AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now());
    service.executeSignal(signal);

    verify(lifecycleManager)
        .transition(any(), eq(OrderStatus.REJECTED), isNull(), contains("Unsupported order type"));
  }

  @Test
  void rejectsOnValidationFailure() {
    var handler = new MarketOrderHandler();
    when(handlerRegistry.getHandler(OrderType.MARKET)).thenReturn(Optional.of(handler));
    when(marketStateTracker.getMarketState("AAPL")).thenReturn(null);

    var signal =
        new OrderSignal("AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now());
    service.executeSignal(signal);

    verify(lifecycleManager)
        .transition(any(), eq(OrderStatus.REJECTED), isNull(), contains("No market data"));
    verify(primaryVenueAdapter, never()).submitOrder(any());
  }

  @Test
  void rejectsOnRiskCheckFailure() {
    var handler = new MarketOrderHandler();
    when(handlerRegistry.getHandler(OrderType.MARKET)).thenReturn(Optional.of(handler));
    when(marketStateTracker.getMarketState("AAPL"))
        .thenReturn(
            new MarketState(
                "AAPL",
                new BigDecimal("149"),
                new BigDecimal("151"),
                new BigDecimal("150"),
                Instant.now()));
    when(riskCheckChain.evaluate(any()))
        .thenReturn(RiskCheckResult.fail("MaxOrderNotional", "Exceeds $100K"));

    var signal =
        new OrderSignal("AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now());
    service.executeSignal(signal);

    verify(lifecycleManager)
        .transition(any(), eq(OrderStatus.REJECTED), isNull(), eq("Exceeds $100K"));
    verify(primaryVenueAdapter, never()).submitOrder(any());
  }

  @Test
  void handlesExchangeRejection() {
    var handler = new MarketOrderHandler();
    when(handlerRegistry.getHandler(OrderType.MARKET)).thenReturn(Optional.of(handler));
    when(marketStateTracker.getMarketState("AAPL"))
        .thenReturn(
            new MarketState(
                "AAPL",
                new BigDecimal("149"),
                new BigDecimal("151"),
                new BigDecimal("150"),
                Instant.now()));
    when(riskCheckChain.evaluate(any())).thenReturn(RiskCheckResult.pass("ALL"));
    when(primaryVenueAdapter.submitOrder(any()))
        .thenReturn(new OrderAck("order-1", "", false, "Insufficient buying power"));

    var signal =
        new OrderSignal("AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now());
    service.executeSignal(signal);

    verify(lifecycleManager)
        .transition(any(), eq(OrderStatus.REJECTED), isNull(), eq("Insufficient buying power"));
  }

  @Test
  void onExecutionReportProcessesFill() {
    var order =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now()));
    order.setExchangeOrderId("SIM-abc");
    when(lifecycleManager.findByExchangeOrderId("SIM-abc")).thenReturn(order);

    var report =
        new ExecutionReport(
            "SIM-abc", new BigDecimal("150.25"), 100, 0, "SIMULATED", Instant.now());
    service.onExecutionReport(report);

    verify(lifecycleManager)
        .transition(eq(order.getOrderId()), eq(OrderStatus.FILLED), any(Fill.class), isNull());
  }

  @Test
  void partialFillSetsPartiallyFilledStatus() {
    var order =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now()));
    order.setExchangeOrderId("SIM-abc");
    when(lifecycleManager.findByExchangeOrderId("SIM-abc")).thenReturn(order);

    var report =
        new ExecutionReport(
            "SIM-abc", new BigDecimal("150.25"), 50, 50, "SIMULATED", Instant.now());
    service.onExecutionReport(report);

    verify(lifecycleManager)
        .transition(
            eq(order.getOrderId()), eq(OrderStatus.PARTIALLY_FILLED), any(Fill.class), isNull());
  }

  @Test
  void rejectsWhenNoAdapterForChosenVenue() {
    var handler = new MarketOrderHandler();
    when(handlerRegistry.getHandler(OrderType.MARKET)).thenReturn(Optional.of(handler));
    when(marketStateTracker.getMarketState("AAPL"))
        .thenReturn(
            new MarketState(
                "AAPL",
                new BigDecimal("149"),
                new BigDecimal("151"),
                new BigDecimal("150"),
                Instant.now()));
    when(riskCheckChain.evaluate(any())).thenReturn(RiskCheckResult.pass("ALL"));
    doAnswer(
            inv -> {
              Order o = inv.getArgument(0);
              o.setVenue("MISSING");
              return RoutingDecision.legacy(o.getOrderId(), "MISSING", "scored", Instant.now());
            })
        .when(router)
        .route(any());
    when(venueAdapters.get("MISSING")).thenReturn(Optional.empty());

    service.executeSignal(
        new OrderSignal(
            "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now()));

    verify(metrics).recordRejection("NoVenueAdapter");
  }

  private static String contains(String substring) {
    return argThat(s -> s != null && s.contains(substring));
  }
}
