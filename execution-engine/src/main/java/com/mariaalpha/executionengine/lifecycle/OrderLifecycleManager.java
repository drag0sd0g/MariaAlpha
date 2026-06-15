package com.mariaalpha.executionengine.lifecycle;

import com.mariaalpha.executionengine.model.Fill;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderEvent;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.publisher.OrderEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderLifecycleManager {

  private static final Logger LOG = LoggerFactory.getLogger(OrderLifecycleManager.class);

  private static final Set<OrderStatus> TERMINAL_STATUSES =
      Set.of(OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED);

  private static final Duration TERMINAL_RETENTION = Duration.ofHours(6);

  private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> orderIdByExchangeId = new ConcurrentHashMap<>();
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

  public void registerExchangeOrderId(String exchangeOrderId, String orderId) {
    if (exchangeOrderId != null && orderId != null) {
      orderIdByExchangeId.put(exchangeOrderId, orderId);
    }
  }

  public void transition(String orderId, OrderStatus newStatus, Fill fill, String reason) {
    var order = getOrder(orderId);
    if (order == null) {
      throw new IllegalArgumentException("Order not found: " + orderId);
    }

    var currentStatus = order.getStatus();
    if (!currentStatus.canTransitionTo(newStatus)) {
      throw new IllegalStateTransitionException(orderId, currentStatus, newStatus);
    }

    if (!order.compareAndSetStatus(currentStatus, newStatus)) {
      throw new IllegalStateTransitionException(orderId, currentStatus, newStatus);
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
    var orderId = orderIdByExchangeId.get(exchangeOrderId);
    if (orderId != null) {
      var order = orders.get(orderId);
      if (order != null) {
        return order;
      }
    }
    var found =
        orders.values().stream()
            .filter(order -> exchangeOrderId.equals(order.getExchangeOrderId()))
            .findFirst()
            .orElse(null);
    if (found != null) {
      orderIdByExchangeId.put(exchangeOrderId, found.getOrderId());
    }
    return found;
  }

  @Scheduled(fixedDelayString = "PT15M")
  void evictStaleTerminalOrders() {
    var cutoff = Instant.now().minus(TERMINAL_RETENTION);
    var before = orders.size();
    orders
        .values()
        .removeIf(
            order ->
                TERMINAL_STATUSES.contains(order.getStatus())
                    && order.getCreatedAt().isBefore(cutoff));
    orderIdByExchangeId.values().removeIf(orderId -> !orders.containsKey(orderId));
    var evicted = before - orders.size();
    if (evicted > 0) {
      LOG.info("Evicted {} terminal orders older than {}", evicted, TERMINAL_RETENTION);
    }
  }

  private void publish(Order order, Fill fill, String reason) {
    var orderEvent =
        new OrderEvent(
            order.getOrderId(), order.getStatus(), order.toSnapshot(), fill, reason, Instant.now());
    publisher.publish(orderEvent);
  }
}
