package com.mariaalpha.executionengine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mariaalpha.executionengine.model.Fill;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderEvent;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.publisher.OrderEventPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderLifecycleManagerTest {

  private OrderEventPublisher publisher;
  private OrderLifecycleManager manager;

  @BeforeEach
  void setUp() {
    publisher = mock(OrderEventPublisher.class);
    manager = new OrderLifecycleManager(publisher);
  }

  @Test
  void registerOrderSetsStatusNew() {
    var order = createOrder();
    manager.registerOrder(order);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);

    var captor = ArgumentCaptor.forClass(OrderEvent.class);
    verify(publisher).publish(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo(OrderStatus.NEW);
  }

  @Test
  void validTransitionPublishesEvent() {
    var order = createOrder();
    manager.registerOrder(order);
    manager.transition(order.getOrderId(), OrderStatus.SUBMITTED, null, null);

    var captor = ArgumentCaptor.forClass(OrderEvent.class);
    verify(publisher, times(2)).publish(captor.capture());
    assertThat(captor.getAllValues().get(1).status()).isEqualTo(OrderStatus.SUBMITTED);
  }

  @Test
  void invalidTransitionThrowsException() {
    var order = createOrder();
    manager.registerOrder(order);
    manager.transition(order.getOrderId(), OrderStatus.SUBMITTED, null, null);
    manager.transition(order.getOrderId(), OrderStatus.FILLED, null, null);

    assertThatThrownBy(
            () -> manager.transition(order.getOrderId(), OrderStatus.SUBMITTED, null, null))
        .isInstanceOf(IllegalStateTransitionException.class);
  }

  @Test
  void fillAddedOnPartialFill() {
    var order = createOrder();
    manager.registerOrder(order);
    manager.transition(order.getOrderId(), OrderStatus.SUBMITTED, null, null);

    var fill =
        new Fill(
            "fill-1",
            order.getOrderId(),
            "AAPL",
            Side.BUY,
            new BigDecimal("150.25"),
            50,
            null,
            "SIMULATED",
            Instant.now());
    manager.transition(order.getOrderId(), OrderStatus.PARTIALLY_FILLED, fill, null);

    assertThat(order.getFilledQuantity()).isEqualTo(50);
    assertThat(order.getFills()).hasSize(1);
  }

  @Test
  void rejectedIncludesReason() {
    var order = createOrder();
    manager.registerOrder(order);
    manager.transition(order.getOrderId(), OrderStatus.REJECTED, null, "Risk check failed");

    var captor = ArgumentCaptor.forClass(OrderEvent.class);
    verify(publisher, times(2)).publish(captor.capture());
    var rejectedEvent = captor.getAllValues().get(1);
    assertThat(rejectedEvent.status()).isEqualTo(OrderStatus.REJECTED);
    assertThat(rejectedEvent.reason()).isEqualTo("Risk check failed");
  }

  @Test
  void getOpenOrderCountAccurate() {
    var o1 = createOrder();
    var o2 = createOrder();
    var o3 = createOrder();
    manager.registerOrder(o1);
    manager.registerOrder(o2);
    manager.registerOrder(o3);

    manager.transition(o1.getOrderId(), OrderStatus.SUBMITTED, null, null);
    manager.transition(o2.getOrderId(), OrderStatus.SUBMITTED, null, null);
    manager.transition(o2.getOrderId(), OrderStatus.FILLED, null, null);
    manager.transition(o3.getOrderId(), OrderStatus.REJECTED, null, "reason");

    // Only o1 is open (SUBMITTED); o2 is FILLED, o3 is REJECTED
    assertThat(manager.getOpenOrderCount()).isEqualTo(1);
  }

  @Test
  void concurrentTransitionsAreSafe() throws Exception {
    var order = createOrder();
    manager.registerOrder(order);
    manager.transition(order.getOrderId(), OrderStatus.SUBMITTED, null, null);

    int threads = 10;
    var latch = new CountDownLatch(threads);
    var successCount = new AtomicInteger(0);
    var executor = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < threads; i++) {
      executor.submit(
          () -> {
            try {
              latch.countDown();
              latch.await();
              manager.transition(order.getOrderId(), OrderStatus.FILLED, null, null);
              successCount.incrementAndGet();
            } catch (Exception ignored) {
              // Expected for losing threads
            }
          });
    }
    executor.shutdown();
    executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

    // Exactly one thread should succeed
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
  }

  private Order createOrder() {
    return new Order(
        new OrderSignal(
            "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
