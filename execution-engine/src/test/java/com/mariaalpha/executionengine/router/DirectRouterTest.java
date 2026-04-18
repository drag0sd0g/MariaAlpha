package com.mariaalpha.executionengine.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.RoutingDecision;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.publisher.RoutingDecisionPublisher;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DirectRouterTest {

  private RoutingDecisionPublisher publisher;
  private DirectRouter router;

  @BeforeEach
  void setUp() {
    publisher = mock(RoutingDecisionPublisher.class);
    router = new DirectRouter(publisher);
  }

  @Test
  void routeReturnsDecision() {
    var order = createOrder();
    var decision = router.route(order);
    assertThat(decision.venue()).isEqualTo("PRIMARY");
    assertThat(decision.orderId()).isEqualTo(order.getOrderId());
  }

  @Test
  void routePublishesDecision() {
    var order = createOrder();
    router.route(order);
    verify(publisher).publish(any(RoutingDecision.class));
  }

  private Order createOrder() {
    return new Order(
        new OrderSignal(
            "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
