package com.mariaalpha.executionengine.service;

import com.mariaalpha.executionengine.adapter.VenueAdapterRegistry;
import com.mariaalpha.executionengine.handler.OrderTypeHandlerRegistry;
import com.mariaalpha.executionengine.lifecycle.IllegalStateTransitionException;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.metrics.ExecutionMetrics;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.Fill;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.risk.DailyLossMonitor;
import com.mariaalpha.executionengine.risk.RiskCheckChain;
import com.mariaalpha.executionengine.router.SmartOrderRouter;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OrderExecutionService {

  private static final Logger LOG = LoggerFactory.getLogger(OrderExecutionService.class);

  private final OrderTypeHandlerRegistry handlerRegistry;
  private final RiskCheckChain riskCheckChain;
  private final SmartOrderRouter router;
  private final VenueAdapterRegistry venueAdapters;
  private final OrderLifecycleManager lifecycleManager;
  private final MarketStateTracker marketStateTracker;
  private final DailyLossMonitor dailyLossMonitor;
  private final ExecutionMetrics metrics;

  public OrderExecutionService(
      OrderTypeHandlerRegistry handlerRegistry,
      RiskCheckChain riskCheckChain,
      SmartOrderRouter router,
      VenueAdapterRegistry venueAdapters,
      OrderLifecycleManager lifecycleManager,
      MarketStateTracker marketStateTracker,
      DailyLossMonitor dailyLossMonitor,
      ExecutionMetrics metrics) {
    this.handlerRegistry = handlerRegistry;
    this.riskCheckChain = riskCheckChain;
    this.router = router;
    this.venueAdapters = venueAdapters;
    this.lifecycleManager = lifecycleManager;
    this.marketStateTracker = marketStateTracker;
    this.dailyLossMonitor = dailyLossMonitor;
    this.metrics = metrics;
  }

  @PostConstruct
  void registerCallbacks() {
    venueAdapters.adapters().forEach(a -> a.onExecutionReport(this::onExecutionReport));
  }

  public void executeSignal(OrderSignal signal) {
    processOrder(new Order(signal));
  }

  public Order submitOrder(Order order) {
    processOrder(order);
    return order;
  }

  private void processOrder(Order order) {
    long startTime = System.currentTimeMillis();

    if (dailyLossMonitor.isTradingHalted()) {
      LOG.warn("Signal rejected - trading halted. Symbol: {}", order.getSymbol());
      metrics.recordRejection("TradingHalted");
      return;
    }

    lifecycleManager.registerOrder(order);

    var handler = handlerRegistry.getHandler(order.getOrderType()).orElse(null);
    if (handler == null) {
      lifecycleManager.transition(
          order.getOrderId(), OrderStatus.REJECTED, null, "Unsupported order type");
      metrics.recordRejection("UnsupportedOrderType");
      return;
    }

    var marketState = marketStateTracker.getMarketState(order.getSymbol());
    var validation = handler.validate(order, marketState);
    if (!validation.valid()) {
      lifecycleManager.transition(
          order.getOrderId(), OrderStatus.REJECTED, null, validation.reason());
      metrics.recordRejection("ValidationFailed");
      return;
    }

    var riskResult = riskCheckChain.evaluate(order);
    if (!riskResult.passed()) {
      lifecycleManager.transition(
          order.getOrderId(), OrderStatus.REJECTED, null, riskResult.reason());
      metrics.recordRejection(riskResult.checkName());
      return;
    }

    var routingDecision = router.route(order);
    var adapter = venueAdapters.get(routingDecision.venue()).orElse(null);
    if (adapter == null) {
      lifecycleManager.transition(
          order.getOrderId(),
          OrderStatus.REJECTED,
          null,
          "No adapter for venue " + routingDecision.venue());
      metrics.recordRejection("NoVenueAdapter");
      return;
    }

    var execInstruction = handler.toExecutionInstruction(order);
    var orderAck = adapter.submitOrder(execInstruction);
    if (orderAck.accepted()) {
      order.setExchangeOrderId(orderAck.exchangeOrderId());
      lifecycleManager.transition(order.getOrderId(), OrderStatus.SUBMITTED, null, null);
      metrics.recordVenueSubmit(adapter.venueName(), adapter.venueType().name());
      LOG.info(
          "Order {} submitted to {} - exchangeId: {}",
          order.getOrderId(),
          adapter.venueName(),
          orderAck.exchangeOrderId());
    } else {
      lifecycleManager.transition(
          order.getOrderId(), OrderStatus.REJECTED, null, orderAck.reason());
      metrics.recordRejection("ExchangeRejected");
    }

    metrics.recordOrderLatency(System.currentTimeMillis() - startTime);
  }

  public void onExecutionReport(ExecutionReport report) {
    var order = lifecycleManager.findByExchangeOrderId(report.exchangeOrderId());
    if (order == null) {
      LOG.warn("Received fill for unknown order: {}", report.exchangeOrderId());
      return;
    }

    // Zero-quantity terminal event: IOC residual cancel, FOK kill, or generic exchange cancel.
    if (report.fillQuantity() == 0 && report.remainingQuantity() == 0) {
      var reason = report.reason() != null ? report.reason() : "exchange-cancel";
      try {
        lifecycleManager.transition(order.getOrderId(), OrderStatus.CANCELLED, null, reason);
      } catch (IllegalStateTransitionException e) {
        // Already terminal (FILLED on prior partial, etc.) — log and continue.
        LOG.debug(
            "Skipping cancel transition for order {} ({})", order.getOrderId(), e.getMessage());
        return;
      }
      switch (reason) {
        case "ioc-residual-cancel" ->
            metrics.recordIocResidualCancelled(order.getSymbol(), order.getSide().name());
        case "fok-killed" -> metrics.recordFokKilled(order.getSymbol(), order.getSide().name());
        default -> {
          /* generic cancel — no dedicated counter */
        }
      }
      return;
    }

    var fill =
        new Fill(
            UUID.randomUUID().toString(),
            order.getOrderId(),
            order.getSymbol(),
            order.getSide(),
            report.fillPrice(),
            report.fillQuantity(),
            report.exchangeOrderId(),
            report.venue(),
            report.timestamp());
    var newStatus =
        report.remainingQuantity() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    lifecycleManager.transition(order.getOrderId(), newStatus, fill, null);

    venueAdapters.adapters().stream()
        .filter(a -> a.venueName().equals(report.venue()))
        .findFirst()
        .ifPresent(a -> metrics.recordVenueFill(a.venueName(), a.venueType().name()));

    dailyLossMonitor.onFill(fill, order.getAvgFillPrice(), order.getSymbol());
  }
}
