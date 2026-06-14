package com.mariaalpha.executionengine.basket;

import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.Order;
import org.springframework.stereotype.Component;

/**
 * Attributes leg fills to their basket. Hooked into {@link
 * com.mariaalpha.executionengine.service.OrderExecutionService#onExecutionReport} alongside the
 * iceberg and pegged coordinators — it is a no-op for any order that is not a tracked basket leg.
 *
 * <p>Unlike the iceberg/pegged coordinators it does not drive any order submission: basket legs are
 * ordinary orders that the {@link BasketTradingService} fans out up front through the standard
 * pipeline. This coordinator only maintains the aggregate read model and metrics as fills arrive.
 */
@Component
public class BasketCoordinator {

  private final BasketRegistry registry;
  private final BasketMetrics metrics;

  public BasketCoordinator(BasketRegistry registry, BasketMetrics metrics) {
    this.registry = registry;
    this.metrics = metrics;
  }

  /**
   * Called after each {@link ExecutionReport} is processed by the execution service. No-op if
   * {@code order} is not a tracked basket leg.
   */
  public void onLegFillIfApplicable(Order order, ExecutionReport report) {
    boolean legComplete = report.remainingQuantity() == 0;
    var basketIdOpt =
        registry.recordLegFill(order.getOrderId(), report.fillQuantity(), legComplete);
    if (basketIdOpt.isEmpty()) {
      return;
    }
    if (legComplete) {
      metrics.recordLegFilled(order.getSymbol(), order.getSide().name());
    }
    var basketId = basketIdOpt.get();
    registry
        .find(basketId)
        .filter(BasketState::isFilled)
        .ifPresent(state -> metrics.recordBasketFilled(basketId));
  }
}
