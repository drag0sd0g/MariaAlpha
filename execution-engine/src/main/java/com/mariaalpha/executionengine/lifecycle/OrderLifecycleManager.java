package com.mariaalpha.executionengine.lifecycle;

import com.mariaalpha.executionengine.model.Fill;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderEvent;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.publisher.OrderEventPublisher;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OrderLifecycleManager {

  private static final Logger LOG = LoggerFactory.getLogger(OrderLifecycleManager.class);

  private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
  private final OrderEventPublisher publisher;

  public OrderLifecycleManager(OrderEventPublisher publisher) {
    this.publisher = publisher;
  }

  public int getOpenOrderCount() {
    return (int)
        orders.values().stream()
            .filter(
                order ->
                    order.getStatus() == OrderStatus.SUBMITTED
                        || order.getStatus() == OrderStatus.PARTIALLY_FILLED)
            .count();
  }

  public void registerOrder(Order order) {
    orders.put(order.getOrderId(), order);
    publish(order, null, null);
  }

  public void transition(String orderId, OrderStatus newStatus, Fill fill, String reason) {
    var order = getOrder(orderId);
    if (order == null) {
      throw new IllegalArgumentException("Order not found: " + orderId);
    }

    var currentStatus = order.getStatus();
    if (!currentStatus.canTransitionTo(newStatus)) {
      throw new IllegalStateTransitionException(orderId, order.getStatus(), newStatus);
    }

    // Atomic CAS
    if (!order.compareAndSetStatus(currentStatus, newStatus)) {
      throw new IllegalStateTransitionException(orderId, order.getStatus(), newStatus);
    }

    if (fill != null) {
      order.addFill(fill);
    }

    LOG.info("Order {} transitioned: {} → {}", orderId, currentStatus, newStatus);
    publish(order, fill, reason);
  }

  public Order getOrder(String orderId) {
    return orders.get(orderId);
  }

  public Order findByExchangeOrderId(String exchangeOrderId) {
    return orders.values().stream()
        .filter(order -> exchangeOrderId.equals(order.getExchangeOrderId()))
        .findFirst()
        .orElse(null);
  }

  private void publish(Order order, Fill fill, String reason) {
    var orderEvent =
        new OrderEvent(
            order.getOrderId(), order.getStatus(), order.toSnapshot(), fill, reason, Instant.now());
    publisher.publish(orderEvent);
  }
}
