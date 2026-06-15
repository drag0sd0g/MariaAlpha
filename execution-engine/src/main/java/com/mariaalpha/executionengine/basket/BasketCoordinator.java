package com.mariaalpha.executionengine.basket;

import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.Order;
import org.springframework.stereotype.Component;

@Component
public class BasketCoordinator {

  private final BasketRegistry registry;
  private final BasketMetrics metrics;

  public BasketCoordinator(BasketRegistry registry, BasketMetrics metrics) {
    this.registry = registry;
    this.metrics = metrics;
  }

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
