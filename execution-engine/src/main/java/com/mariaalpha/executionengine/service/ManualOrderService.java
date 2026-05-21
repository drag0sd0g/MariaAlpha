package com.mariaalpha.executionengine.service;

import com.mariaalpha.executionengine.controller.dto.SubmitOrderRequest;
import com.mariaalpha.executionengine.controller.dto.SubmitOrderResponse;
import com.mariaalpha.executionengine.iceberg.IcebergCoordinator;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ManualOrderService {

  private static final Set<OrderStatus> TERMINAL_STATUSES =
      Set.of(OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED);

  private final OrderExecutionService executionService;
  private final OrderLifecycleManager lifecycleManager;
  private final IcebergCoordinator icebergCoordinator;

  public ManualOrderService(
      OrderExecutionService executionService,
      OrderLifecycleManager lifecycleManager,
      IcebergCoordinator icebergCoordinator) {
    this.executionService = executionService;
    this.lifecycleManager = lifecycleManager;
    this.icebergCoordinator = icebergCoordinator;
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
            Instant.now(),
            request.displayQuantity(),
            request.tif(),
            null);

    var order = executionService.submitOrder(new Order(signal));
    return new SubmitOrderResponse(order.getOrderId(), order.getStatus(), signal.timestamp());
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
      if (order.getOrderType() == OrderType.ICEBERG && icebergCoordinator != null) {
        icebergCoordinator.onParentCancelRequested(order);
        return true;
      }
      lifecycleManager.transition(orderId, OrderStatus.CANCELLED, null, "Manual cancel");
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
