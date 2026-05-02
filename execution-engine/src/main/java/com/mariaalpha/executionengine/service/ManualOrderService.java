package com.mariaalpha.executionengine.service;

import com.mariaalpha.executionengine.controller.dto.SubmitOrderRequest;
import com.mariaalpha.executionengine.controller.dto.SubmitOrderResponse;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ManualOrderService {

  private static final Set<OrderStatus> TERMINAL_STATUSES =
      Set.of(OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED);

  private final OrderExecutionService executionService;
  private final OrderLifecycleManager lifecycleManager;

  public ManualOrderService(
      OrderExecutionService executionService, OrderLifecycleManager lifecycleManager) {
    this.executionService = executionService;
    this.lifecycleManager = lifecycleManager;
  }

  public SubmitOrderResponse submit(SubmitOrderRequest request) {
    if (request.orderType().name().equals("LIMIT") && request.limitPrice() == null) {
      throw new IllegalArgumentException("LIMIT orders require a limitPrice");
    }
    if (request.orderType().name().equals("STOP") && request.stopPrice() == null) {
      throw new IllegalArgumentException("STOP orders require a stopPrice");
    }

    var signal =
        new OrderSignal(
            request.symbol(),
            request.side(),
            request.quantity(),
            request.orderType(),
            request.limitPrice(),
            request.stopPrice(),
            "MANUAL",
            Instant.now());

    var order = executionService.submitOrder(new Order(signal));
    return new SubmitOrderResponse(order.getOrderId(), order.getStatus(), Instant.now());
  }

  public boolean cancel(String orderId) {
    var order = lifecycleManager.getOrder(orderId);
    if (order == null) {
      return false;
    }
    if (TERMINAL_STATUSES.contains(order.getStatus())) {
      return false;
    }
    try {
      lifecycleManager.transition(orderId, OrderStatus.CANCELLED, null, "Manual cancel");
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
