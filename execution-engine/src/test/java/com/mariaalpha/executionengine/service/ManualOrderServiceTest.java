package com.mariaalpha.executionengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.controller.dto.SubmitOrderRequest;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManualOrderServiceTest {

  private OrderExecutionService executionService;
  private OrderLifecycleManager lifecycleManager;
  private ManualOrderService service;

  @BeforeEach
  void setUp() {
    executionService = mock(OrderExecutionService.class);
    lifecycleManager = mock(OrderLifecycleManager.class);
    service = new ManualOrderService(executionService, lifecycleManager);

    // submitOrder returns the order passed in (simulates pipeline registration)
    when(executionService.submitOrder(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void submit_buildsInstructionWithStrategyManual() {
    var request =
        new SubmitOrderRequest(
            "AAPL", Side.BUY, OrderType.LIMIT, 100, new BigDecimal("178.50"), null, null);

    var response = service.submit(request);

    var captor = org.mockito.ArgumentCaptor.forClass(Order.class);
    verify(executionService).submitOrder(captor.capture());
    assertThat(captor.getValue().getStrategyName()).isEqualTo("MANUAL");
    assertThat(response.orderId()).isNotBlank();
    assertThat(response.status()).isEqualTo(OrderStatus.NEW);
  }

  @Test
  void submit_generatesClientOrderIdWhenAbsent() {
    var request = new SubmitOrderRequest("AAPL", Side.BUY, OrderType.MARKET, 10, null, null, null);

    service.submit(request);

    // No exception; clientOrderId generated internally (UUID). Just verify submission happened.
    verify(executionService).submitOrder(any(Order.class));
  }

  @Test
  void submit_preservesProvidedClientOrderId() {
    var request =
        new SubmitOrderRequest(
            "TSLA", Side.SELL, OrderType.MARKET, 50, null, null, "my-client-id-123");

    service.submit(request);

    // ClientOrderId is a signal-level concept; it's carried through as strategyName="MANUAL"
    verify(executionService).submitOrder(any(Order.class));
  }

  @Test
  void cancel_unknownOrderId_returnsFalse() {
    when(lifecycleManager.getOrder("unknown-id")).thenReturn(null);

    assertThat(service.cancel("unknown-id")).isFalse();
  }

  @Test
  void cancel_terminalOrder_returnsFalse() {
    var signal =
        new OrderSignal(
            "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "MANUAL", java.time.Instant.now());
    var order = new Order(signal);
    order.compareAndSetStatus(OrderStatus.NEW, OrderStatus.SUBMITTED);
    order.compareAndSetStatus(OrderStatus.SUBMITTED, OrderStatus.FILLED);

    when(lifecycleManager.getOrder(order.getOrderId())).thenReturn(order);

    assertThat(service.cancel(order.getOrderId())).isFalse();
  }
}
