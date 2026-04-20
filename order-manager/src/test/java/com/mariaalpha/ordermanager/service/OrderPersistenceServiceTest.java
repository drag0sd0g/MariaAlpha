package com.mariaalpha.ordermanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.ordermanager.entity.FillEntity;
import com.mariaalpha.ordermanager.entity.OrderEntity;
import com.mariaalpha.ordermanager.metrics.OrderManagerMetrics;
import com.mariaalpha.ordermanager.model.FillEvent;
import com.mariaalpha.ordermanager.model.OrderLifecycleEvent;
import com.mariaalpha.ordermanager.model.OrderSnapshotEvent;
import com.mariaalpha.ordermanager.model.OrderStatus;
import com.mariaalpha.ordermanager.model.OrderType;
import com.mariaalpha.ordermanager.model.Side;
import com.mariaalpha.ordermanager.repository.FillRepository;
import com.mariaalpha.ordermanager.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderPersistenceServiceTest {

  @Mock private OrderRepository orderRepository;
  @Mock private FillRepository fillRepository;
  @Mock private OrderManagerMetrics metrics;

  private OrderPersistenceService service;

  @BeforeEach
  void setUp() {
    service = new OrderPersistenceService(orderRepository, fillRepository, metrics);
  }

  @Test
  void upsertCreatesNewOrderOnFirstSight() {
    var orderId = UUID.randomUUID();
    var event = newLifecycleEvent(orderId, OrderStatus.NEW, 0, null, null);
    when(orderRepository.findByClientOrderId("client-1")).thenReturn(Optional.empty());
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    var result = service.upsertOrder(event);
    assertThat(result.getOrderId()).isEqualTo(orderId);
    assertThat(result.getClientOrderId()).isEqualTo("client-1");
    assertThat(result.getStatus()).isEqualTo(OrderStatus.NEW);
    verify(metrics).recordOrderPersisted("BUY");
  }

  @Test
  void upsertUpdatesExistingOrderStatus() {
    var orderId = UUID.randomUUID();
    var existing = existingOrder(orderId, OrderStatus.NEW);
    var event = newLifecycleEvent(orderId, OrderStatus.SUBMITTED, 0, null, null);
    when(orderRepository.findByClientOrderId("client-1")).thenReturn(Optional.of(existing));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    var result = service.upsertOrder(event);
    assertThat(result.getStatus()).isEqualTo(OrderStatus.SUBMITTED);
    verify(metrics, never()).recordOrderPersisted(anyString());
  }

  @Test
  void upsertDropsIllegalStatusTransition() {
    var orderId = UUID.randomUUID();
    var existing = existingOrder(orderId, OrderStatus.FILLED);
    var event = newLifecycleEvent(orderId, OrderStatus.SUBMITTED, 0, null, null);
    when(orderRepository.findByClientOrderId("client-1")).thenReturn(Optional.of(existing));

    var result = service.upsertOrder(event);
    assertThat(result.getStatus()).isEqualTo(OrderStatus.FILLED);
    verify(orderRepository, never()).save(any());
  }

  @Test
  void persistFillReturnsEmptyWhenFillEventIsNull() {
    var order = existingOrder(UUID.randomUUID(), OrderStatus.SUBMITTED);
    assertThat(service.persistFillIfAbsent(order, null)).isEmpty();
  }

  @Test
  void persistFillDeduplicatesByExchangeFillId() {
    var order = existingOrder(UUID.randomUUID(), OrderStatus.PARTIALLY_FILLED);
    var fill = newFillEvent("FILL-1", "EX-F-1");
    when(fillRepository.existsByExchangeFillId("EX-F-1")).thenReturn(true);

    assertThat(service.persistFillIfAbsent(order, fill)).isEmpty();
    verify(fillRepository, never()).save(any());
  }

  @Test
  void persistFillSavesNewFillAndRecordsMetric() {
    var order = existingOrder(UUID.randomUUID(), OrderStatus.PARTIALLY_FILLED);
    var fill = newFillEvent("FILL-1", "EX-F-1");
    when(fillRepository.existsByExchangeFillId("EX-F-1")).thenReturn(false);
    when(fillRepository.save(any(FillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Optional<FillEntity> result = service.persistFillIfAbsent(order, fill);
    assertThat(result).isPresent();
    assertThat(result.get().getExchangeFillId()).isEqualTo("EX-F-1");
    verify(metrics).recordFillPersisted("BUY", "SIMULATED");
  }

  @Test
  void persistFillHandlesNullExchangeFillId() {
    var order = existingOrder(UUID.randomUUID(), OrderStatus.PARTIALLY_FILLED);
    var fill = newFillEvent("FILL-1", null);
    when(fillRepository.save(any(FillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Optional<FillEntity> result = service.persistFillIfAbsent(order, fill);
    assertThat(result).isPresent();
    assertThat(result.get().getFillPrice()).isEqualByComparingTo("150");
  }

  @Test
  void upsertRecordsPersistDuration() {
    var orderId = UUID.randomUUID();
    var event = newLifecycleEvent(orderId, OrderStatus.NEW, 0, null, null);
    when(orderRepository.findByClientOrderId("client-1")).thenReturn(Optional.empty());
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    service.upsertOrder(event);
    verify(metrics).recordOrderPersistDuration(org.mockito.ArgumentMatchers.anyLong());
  }

  private OrderEntity existingOrder(UUID orderId, OrderStatus status) {
    var order = new OrderEntity();
    order.setOrderId(orderId);
    order.setClientOrderId("client-1");
    order.setSymbol("AAPL");
    order.setSide(Side.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setQuantity(BigDecimal.valueOf(100));
    order.setStatus(status);
    order.setFilledQuantity(BigDecimal.ZERO);
    return order;
  }

  private OrderLifecycleEvent newLifecycleEvent(
      UUID orderId, OrderStatus status, int filledQty, BigDecimal avgFill, FillEvent fill) {
    OrderSnapshotEvent snapshot =
        new OrderSnapshotEvent(
            orderId.toString(),
            "client-1",
            "AAPL",
            Side.BUY,
            100,
            OrderType.LIMIT,
            BigDecimal.valueOf(150),
            null,
            "VWAP",
            filledQty,
            avgFill,
            "EX-" + orderId,
            "SIMULATED");
    return new OrderLifecycleEvent(orderId.toString(), status, snapshot, fill, null, Instant.now());
  }

  private FillEvent newFillEvent(String fillId, String exchangeFillId) {
    return new FillEvent(
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        exchangeFillId,
        "AAPL",
        Side.BUY,
        BigDecimal.valueOf(150),
        10,
        BigDecimal.ZERO,
        "SIMULATED",
        Instant.now());
  }
}
