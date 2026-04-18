package com.mariaalpha.executionengine.service;

import com.mariaalpha.executionengine.adapter.ExchangeAdapter;
import com.mariaalpha.executionengine.handler.OrderTypeHandlerRegistry;
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
  private final ExchangeAdapter exchangeAdapter;
  private final OrderLifecycleManager lifecycleManager;
  private final MarketStateTracker marketStateTracker;
  private final DailyLossMonitor dailyLossMonitor;
  private final ExecutionMetrics metrics;

  public OrderExecutionService(
      OrderTypeHandlerRegistry handlerRegistry,
      RiskCheckChain riskCheckChain,
      SmartOrderRouter router,
      ExchangeAdapter exchangeAdapter,
      OrderLifecycleManager lifecycleManager,
      MarketStateTracker marketStateTracker,
      DailyLossMonitor dailyLossMonitor,
      ExecutionMetrics metrics) {
    this.handlerRegistry = handlerRegistry;
    this.riskCheckChain = riskCheckChain;
    this.router = router;
    this.exchangeAdapter = exchangeAdapter;
    this.lifecycleManager = lifecycleManager;
    this.marketStateTracker = marketStateTracker;
    this.dailyLossMonitor = dailyLossMonitor;
    this.metrics = metrics;

    // Register fill callback so exchange adapter pushes fills to us
    this.exchangeAdapter.onExecutionReport(this::onExecutionReport);
  }

  public void executeSignal(OrderSignal signal) {
    long startTime = System.currentTimeMillis();

    // 0. Check trading halt
    if (dailyLossMonitor.isTradingHalted()) {
      LOG.warn("Signal rejected - trading halted. Symbol: {}", signal.symbol());
      metrics.recordRejection("TradingHalted");
      return;
    }

    // 1. Create order
    var order = new Order(signal);
    lifecycleManager.registerOrder(order);

    // 2. Validate via order type handler
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

    // 3. Risk check chain
    var riskResult = riskCheckChain.evaluate(order);
    if (!riskResult.passed()) {
      lifecycleManager.transition(
          order.getOrderId(), OrderStatus.REJECTED, null, riskResult.reason());
      metrics.recordRejection(riskResult.checkName());
      return;
    }

    // 4. Route
    var routingDecision = router.route(order);

    // 5. Build execution instruction and submit
    var execInstruction = handler.toExecutionInstruction(order);
    var orderAck = exchangeAdapter.submitOrder(execInstruction);
    if (orderAck.accepted()) {
      order.setExchangeOrderId(orderAck.exchangeOrderId());
      lifecycleManager.transition(order.getOrderId(), OrderStatus.SUBMITTED, null, null);
      LOG.info(
          "Order {} submitted to {} - exchangeId: {}",
          order.getOrderId(),
          routingDecision.venue(),
          orderAck.exchangeOrderId());
    } else {
      lifecycleManager.transition(
          order.getOrderId(), OrderStatus.REJECTED, null, orderAck.reason());
      metrics.recordRejection("ExchangeRejected");
    }

    metrics.recordOrderLatency(System.currentTimeMillis() - startTime);
  }

  /** Called by the exchange adapter when a fill arrives. */
  public void onExecutionReport(ExecutionReport report) {
    var order = lifecycleManager.findByExchangeOrderId(report.exchangeOrderId());
    if (order == null) {
      LOG.warn("Received fill for unknown order: {}", report.exchangeOrderId());
      return;
    }

    var fill =
        new Fill(
            UUID.randomUUID().toString(),
            order.getOrderId(),
            report.fillPrice(),
            report.fillQuantity(),
            report.venue(),
            report.timestamp());
    var newStatus =
        report.remainingQuantity() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    lifecycleManager.transition(order.getOrderId(), newStatus, fill, null);

    // Update daily p&l
    dailyLossMonitor.onFill(fill, order.getAvgFillPrice(), order.getSymbol());
  }
}
