package com.mariaalpha.ordermanager.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.ordermanager.controller.dto.PositionSnapshot;
import com.mariaalpha.ordermanager.entity.FillEntity;
import com.mariaalpha.ordermanager.entity.OrderEntity;
import com.mariaalpha.ordermanager.entity.PositionEntity;
import com.mariaalpha.ordermanager.model.FillEvent;
import com.mariaalpha.ordermanager.model.OrderLifecycleEvent;
import com.mariaalpha.ordermanager.model.OrderSnapshotEvent;
import com.mariaalpha.ordermanager.model.OrderStatus;
import com.mariaalpha.ordermanager.model.OrderType;
import com.mariaalpha.ordermanager.model.Side;
import com.mariaalpha.ordermanager.publisher.PositionUpdatePublisher;
import com.mariaalpha.ordermanager.service.OrderPersistenceService;
import com.mariaalpha.ordermanager.service.PositionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderLifecycleConsumerTest {

  @Mock private OrderPersistenceService persistenceService;
  @Mock private PositionService positionService;
  @Mock private PositionUpdatePublisher publisher;

  private ObjectMapper objectMapper;
  private OrderLifecycleConsumer consumer;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    consumer =
        new OrderLifecycleConsumer(objectMapper, persistenceService, positionService, publisher);
  }

  @Test
  void lifecycleEventWithoutFillDoesNotTouchPositionService() throws Exception {
    var event = lifecycleEvent(OrderStatus.SUBMITTED, null);
    var saved = newOrder(UUID.fromString(event.orderId()));
    when(persistenceService.upsertOrder(event)).thenReturn(saved);
    when(persistenceService.persistFillIfAbsent(saved, null)).thenReturn(Optional.empty());

    consumer.handle(event);

    verify(positionService, never()).applyFill(any());
    verify(publisher, never()).publish(any());
  }

  @Test
  void lifecycleEventWithFillAppliesPositionAndPublishes() throws Exception {
    var fillEvent = newFillEvent();
    var event = lifecycleEvent(OrderStatus.PARTIALLY_FILLED, fillEvent);
    var saved = newOrder(UUID.fromString(event.orderId()));
    var persistedFill = newPersistedFill(saved);
    var position = newPosition(saved.getSymbol());

    when(persistenceService.upsertOrder(event)).thenReturn(saved);
    when(persistenceService.persistFillIfAbsent(saved, fillEvent))
        .thenReturn(Optional.of(persistedFill));
    when(positionService.applyFill(persistedFill)).thenReturn(position);

    consumer.handle(event);

    verify(positionService).applyFill(persistedFill);
    verify(publisher).publish(any(PositionSnapshot.class));
  }

  @Test
  void nullEventIsDropped() {
    consumer.handle(null);
    verify(persistenceService, never()).upsertOrder(any());
  }

  @Test
  void eventWithNullOrderSnapshotIsDropped() {
    var event =
        new OrderLifecycleEvent(
            UUID.randomUUID().toString(), OrderStatus.REJECTED, null, null, "bad", Instant.now());
    consumer.handle(event);
    verify(persistenceService, never()).upsertOrder(any());
  }

  @Test
  void jsonParseFailureDoesNotPropagate() {
    ConsumerRecord<String, String> bad =
        new ConsumerRecord<>("orders.lifecycle", 0, 0, "k", "not json");
    consumer.onLifecycleEvent(bad);
    verify(persistenceService, never()).upsertOrder(any());
  }

  @Test
  void validJsonDispatchesHandler() throws Exception {
    var event = lifecycleEvent(OrderStatus.SUBMITTED, null);
    var saved = newOrder(UUID.fromString(event.orderId()));
    when(persistenceService.upsertOrder(any())).thenReturn(saved);
    when(persistenceService.persistFillIfAbsent(any(), any())).thenReturn(Optional.empty());

    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("orders.lifecycle", 0, 0, "k", objectMapper.writeValueAsString(event));
    consumer.onLifecycleEvent(record);

    verify(persistenceService).upsertOrder(any());
  }

  private OrderLifecycleEvent lifecycleEvent(OrderStatus status, FillEvent fill) {
    UUID id = UUID.randomUUID();
    var snap =
        new OrderSnapshotEvent(
            id.toString(),
            "c-" + id,
            "AAPL",
            Side.BUY,
            100,
            OrderType.LIMIT,
            BigDecimal.valueOf(150),
            null,
            "VWAP",
            fill != null ? fill.fillQuantity() : 0,
            fill != null ? fill.fillPrice() : null,
            "EX-" + id,
            "SIMULATED");
    return new OrderLifecycleEvent(id.toString(), status, snap, fill, null, Instant.now());
  }

  private FillEvent newFillEvent() {
    return new FillEvent(
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "EX-F-1",
        "AAPL",
        Side.BUY,
        BigDecimal.valueOf(150),
        10,
        BigDecimal.ZERO,
        "SIMULATED",
        Instant.now());
  }

  private OrderEntity newOrder(UUID id) {
    var order = new OrderEntity();
    order.setOrderId(id);
    order.setSymbol("AAPL");
    order.setSide(Side.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setQuantity(BigDecimal.valueOf(100));
    order.setStatus(OrderStatus.PARTIALLY_FILLED);
    return order;
  }

  private FillEntity newPersistedFill(OrderEntity order) {
    var fill = new FillEntity();
    fill.setFillId(UUID.randomUUID());
    fill.setOrder(order);
    fill.setSymbol(order.getSymbol());
    fill.setSide(order.getSide());
    fill.setFillPrice(BigDecimal.valueOf(150));
    fill.setFillQuantity(BigDecimal.valueOf(10));
    fill.setCommission(BigDecimal.ZERO);
    fill.setFilledAt(Instant.now());
    return fill;
  }

  private PositionEntity newPosition(String symbol) {
    var p = new PositionEntity(symbol);
    p.setNetQuantity(BigDecimal.valueOf(10));
    p.setAvgEntryPrice(BigDecimal.valueOf(150));
    p.setRealizedPnl(BigDecimal.ZERO);
    p.setUnrealizedPnl(BigDecimal.ZERO);
    p.setLastMarkPrice(BigDecimal.valueOf(150));
    return p;
  }
}
