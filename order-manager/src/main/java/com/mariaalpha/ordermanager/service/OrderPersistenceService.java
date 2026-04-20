package com.mariaalpha.ordermanager.service;

import com.mariaalpha.ordermanager.entity.FillEntity;
import com.mariaalpha.ordermanager.entity.OrderEntity;
import com.mariaalpha.ordermanager.metrics.OrderManagerMetrics;
import com.mariaalpha.ordermanager.model.FillEvent;
import com.mariaalpha.ordermanager.model.OrderLifecycleEvent;
import com.mariaalpha.ordermanager.model.OrderSnapshotEvent;
import com.mariaalpha.ordermanager.repository.FillRepository;
import com.mariaalpha.ordermanager.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderPersistenceService {

  private static final Logger LOG = LoggerFactory.getLogger(OrderPersistenceService.class);

  private final OrderRepository orderRepository;
  private final FillRepository fillRepository;
  private final OrderManagerMetrics metrics;

  public OrderPersistenceService(
      OrderRepository orderRepository, FillRepository fillRepository, OrderManagerMetrics metrics) {
    this.orderRepository = orderRepository;
    this.fillRepository = fillRepository;
    this.metrics = metrics;
  }

  @Transactional
  public OrderEntity upsertOrder(OrderLifecycleEvent event) {
    long start = System.currentTimeMillis();
    try {
      var snapshot = requireSnapshot(event);

      var existing = orderRepository.findByClientOrderId(snapshot.clientOrderId());
      var isNew = existing.isEmpty();
      var order = isNew ? createOrderFromSnapshot(snapshot) : existing.get();

      if (isIllegalTransition(order, isNew, event)) {
        LOG.warn(
            "Illegal status transition for order {}: {} -> {} (dropping)",
            order.getOrderId(),
            order.getStatus(),
            event.status());
        return order;
      }

      applyMutableFields(order, snapshot, event);

      var saved = orderRepository.save(order);

      if (isNew) {
        metrics.recordOrderPersisted(snapshot.side().name());
      }

      return saved;
    } finally {
      metrics.recordOrderPersistDuration(System.currentTimeMillis() - start);
    }
  }

  // Guard: every lifecycle event must carry a full order snapshot
  private OrderSnapshotEvent requireSnapshot(OrderLifecycleEvent event) {
    if (event.order() == null) {
      throw new IllegalArgumentException(
          "Lifecycle event has null order snapshot: " + event.orderId());
    }
    return event.order();
  }

  // Populate the immutable identity fields that are set once at order creation
  private OrderEntity createOrderFromSnapshot(OrderSnapshotEvent snapshot) {
    var order = new OrderEntity();
    order.setOrderId(UUID.fromString(snapshot.orderId()));
    order.setClientOrderId(snapshot.clientOrderId());
    order.setSymbol(snapshot.symbol());
    order.setSide(snapshot.side());
    order.setOrderType(snapshot.orderType());
    order.setQuantity(BigDecimal.valueOf(snapshot.quantity()));
    order.setStrategy(snapshot.strategyName());
    return order;
  }

  // Reject transitions that violate the order status state machine (e.g. FILLED -> NEW)
  private boolean isIllegalTransition(OrderEntity order, boolean isNew, OrderLifecycleEvent event) {
    return !isNew
        && order.getStatus() != null
        && !order.getStatus().canTransitionTo(event.status());
  }

  // Update the fields that can legitimately change with each lifecycle event
  private void applyMutableFields(
      OrderEntity order, OrderSnapshotEvent snapshot, OrderLifecycleEvent event) {
    order.setStatus(event.status());
    order.setLimitPrice(snapshot.limitPrice());
    order.setStopPrice(snapshot.stopPrice());
    order.setFilledQuantity(BigDecimal.valueOf(snapshot.filledQuantity()));
    order.setAvgFillPrice(snapshot.avgFillPrice());
    order.setExchangeOrderId(snapshot.exchangeOrderId());
    order.setVenue(snapshot.venue());
  }

  @Transactional
  public Optional<FillEntity> persistFillIfAbsent(OrderEntity order, FillEvent fillEvent) {
    if (fillEvent == null) {
      return Optional.empty();
    }
    if (isDuplicateFill(fillEvent, order)) {
      return Optional.empty();
    }
    var fill = buildFillEntity(order, fillEvent);
    var saved = fillRepository.save(fill);
    metrics.recordFillPersisted(
        fillEvent.side().name(), fillEvent.venue() != null ? fillEvent.venue() : "unknown");
    return Optional.of(saved);
  }

  // Idempotency guard: if the exchange already reported this fill ID, skip it to avoid
  // double-counting
  private boolean isDuplicateFill(FillEvent fillEvent, OrderEntity order) {
    if (fillEvent.exchangeFillId() != null
        && fillRepository.existsByExchangeFillId(fillEvent.exchangeFillId())) {
      LOG.debug(
          "Skipping duplicate fill exchangeFillId={} for order {}",
          fillEvent.exchangeFillId(),
          order.getOrderId());
      return true;
    }
    return false;
  }

  // Map the fill event onto a new FillEntity; fall back to a generated ID / current time if the
  // exchange omitted them
  private FillEntity buildFillEntity(OrderEntity order, FillEvent fillEvent) {
    var fill = new FillEntity();
    if (fillEvent.fillId() != null) {
      fill.setFillId(UUID.fromString(fillEvent.fillId()));
    }
    fill.setOrder(order);
    fill.setSymbol(fillEvent.symbol());
    fill.setSide(fillEvent.side());
    fill.setFillPrice(fillEvent.fillPrice());
    fill.setFillQuantity(BigDecimal.valueOf(fillEvent.fillQuantity()));
    fill.setCommission(fillEvent.commission() != null ? fillEvent.commission() : BigDecimal.ZERO);
    fill.setVenue(fillEvent.venue());
    fill.setExchangeFillId(fillEvent.exchangeFillId());
    fill.setFilledAt(fillEvent.filledAt() != null ? fillEvent.filledAt() : Instant.now());
    return fill;
  }
}
