package com.mariaalpha.executionengine.service;

import com.mariaalpha.executionengine.basket.BasketMetrics;
import com.mariaalpha.executionengine.basket.BasketRegistry;
import com.mariaalpha.executionengine.basket.BasketState;
import com.mariaalpha.executionengine.basket.BasketView;
import com.mariaalpha.executionengine.controller.dto.BasketLegRequest;
import com.mariaalpha.executionengine.controller.dto.BasketOrderRequest;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BasketTradingService {

  private static final Logger LOG = LoggerFactory.getLogger(BasketTradingService.class);

  private final OrderExecutionService executionService;
  private final BasketRegistry registry;
  private final BasketMetrics metrics;

  public BasketTradingService(
      OrderExecutionService executionService, BasketRegistry registry, BasketMetrics metrics) {
    this.executionService = executionService;
    this.registry = registry;
    this.metrics = metrics;
  }

  public BasketView submit(BasketOrderRequest request) {
    if (request.legs() == null || request.legs().isEmpty()) {
      throw new IllegalArgumentException("Basket order requires at least one leg");
    }
    request.legs().forEach(BasketTradingService::validateLeg);

    var basketId = UUID.randomUUID().toString();
    var state = new BasketState(basketId, request.name(), Instant.now());
    registry.register(state);

    for (var leg : request.legs()) {
      submitLeg(basketId, state, leg);
    }

    metrics.recordBasketSubmitted(basketId, request.legs().size());
    LOG.info(
        "Basket {} submitted with {} legs ({} accepted)",
        basketId,
        request.legs().size(),
        state.toView().acceptedLegs());
    return state.toView();
  }

  public Optional<BasketView> get(String basketId) {
    return registry.view(basketId);
  }

  public List<BasketView> list() {
    return registry.all();
  }

  private void submitLeg(String basketId, BasketState state, BasketLegRequest leg) {
    var signal =
        new OrderSignal(
            leg.symbol(),
            leg.side(),
            leg.quantity(),
            leg.orderType(),
            leg.limitPrice(),
            leg.stopPrice(),
            "BASKET",
            Instant.now(),
            null,
            leg.tif(),
            null);
    var order = new Order(signal);
    state.addLeg(order.getOrderId(), leg.symbol(), leg.side(), leg.quantity());
    registry.linkLeg(order.getOrderId(), basketId);

    executionService.submitOrder(order);

    var status = order.getStatus();
    state.recordSubmissionOutcome(
        order.getOrderId(),
        status,
        status == OrderStatus.REJECTED ? "rejected at submission" : null);
    if (status == OrderStatus.REJECTED) {
      metrics.recordLegRejected(leg.symbol());
    }
  }

  private static void validateLeg(BasketLegRequest leg) {
    if (leg.orderType() == OrderType.ICEBERG || leg.orderType() == OrderType.PEGGED) {
      throw new IllegalArgumentException(
          "Basket legs do not support the " + leg.orderType() + " order type");
    }
    if (leg.orderType() == OrderType.LIMIT && leg.limitPrice() == null) {
      throw new IllegalArgumentException("LIMIT leg " + leg.symbol() + " requires a limitPrice");
    }
    if (leg.orderType() == OrderType.STOP && leg.stopPrice() == null) {
      throw new IllegalArgumentException("STOP leg " + leg.symbol() + " requires a stopPrice");
    }
  }
}
