package com.mariaalpha.posttrade.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.posttrade.model.OrderLifecycleEvent;
import com.mariaalpha.posttrade.model.OrderSnapshotEvent;
import com.mariaalpha.posttrade.model.OrderStatus;
import com.mariaalpha.posttrade.model.OrderType;
import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.service.ArrivalSnapshotService;
import com.mariaalpha.posttrade.service.TcaService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderLifecycleConsumerTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void capturesArrivalOnNonTerminal() {
    ArrivalSnapshotService arrival = mock(ArrivalSnapshotService.class);
    TcaService tca = mock(TcaService.class);
    OrderLifecycleConsumer consumer = new OrderLifecycleConsumer(mapper, arrival, tca);
    UUID orderId = UUID.randomUUID();
    consumer.handle(event(orderId, OrderStatus.NEW));
    verify(arrival).captureIfAbsent(any());
    verify(tca, never()).computeForCompletedOrder(any());
  }

  @Test
  void triggersTcaOnFilled() {
    ArrivalSnapshotService arrival = mock(ArrivalSnapshotService.class);
    TcaService tca = mock(TcaService.class);
    OrderLifecycleConsumer consumer = new OrderLifecycleConsumer(mapper, arrival, tca);
    UUID orderId = UUID.randomUUID();
    consumer.handle(event(orderId, OrderStatus.FILLED));
    verify(arrival).captureIfAbsent(any());
    verify(tca).computeForCompletedOrder(eq(orderId));
  }

  @Test
  void doesNotTriggerTcaOnCancelled() {
    ArrivalSnapshotService arrival = mock(ArrivalSnapshotService.class);
    TcaService tca = mock(TcaService.class);
    OrderLifecycleConsumer consumer = new OrderLifecycleConsumer(mapper, arrival, tca);
    consumer.handle(event(UUID.randomUUID(), OrderStatus.CANCELLED));
    verify(tca, never()).computeForCompletedOrder(any());
  }

  @Test
  void swallowsMalformedUuid() {
    ArrivalSnapshotService arrival = mock(ArrivalSnapshotService.class);
    TcaService tca = mock(TcaService.class);
    OrderLifecycleConsumer consumer = new OrderLifecycleConsumer(mapper, arrival, tca);
    OrderSnapshotEvent snap =
        new OrderSnapshotEvent(
            "not-a-uuid",
            "c-1",
            "AAPL",
            Side.BUY,
            100,
            OrderType.MARKET,
            null,
            null,
            "VWAP",
            100,
            null,
            "x",
            "ALPACA");
    OrderLifecycleEvent bad =
        new OrderLifecycleEvent("not-a-uuid", OrderStatus.FILLED, snap, null, null, Instant.now());
    consumer.handle(bad);
    verify(tca, never()).computeForCompletedOrder(any());
  }

  private static OrderLifecycleEvent event(UUID orderId, OrderStatus status) {
    OrderSnapshotEvent snap =
        new OrderSnapshotEvent(
            orderId.toString(),
            "c-1",
            "AAPL",
            Side.BUY,
            1000,
            OrderType.MARKET,
            null,
            null,
            "VWAP",
            1000,
            null,
            "x",
            "ALPACA");
    return new OrderLifecycleEvent(orderId.toString(), status, snap, null, null, Instant.now());
  }
}
