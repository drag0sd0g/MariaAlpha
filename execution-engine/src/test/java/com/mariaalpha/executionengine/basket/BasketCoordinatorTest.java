package com.mariaalpha.executionengine.basket;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BasketCoordinatorTest {

  private BasketRegistry registry;
  private BasketCoordinator coordinator;

  @BeforeEach
  void setUp() {
    registry = new BasketRegistry();
    coordinator = new BasketCoordinator(registry, new BasketMetrics(new SimpleMeterRegistry()));
  }

  private Order trackedLeg(String basketId, int qty) {
    var order =
        new Order(
            new OrderSignal(
                "AAPL",
                Side.BUY,
                qty,
                OrderType.LIMIT,
                new BigDecimal("150.00"),
                null,
                "BASKET",
                Instant.now()));
    var state = new BasketState(basketId, "test", Instant.now());
    state.addLeg(order.getOrderId(), "AAPL", Side.BUY, qty);
    registry.register(state);
    registry.linkLeg(order.getOrderId(), basketId);
    return order;
  }

  @Test
  void onLegFill_full_marksLegAndBasketFilled() {
    var leg = trackedLeg("b1", 100);

    coordinator.onLegFillIfApplicable(
        leg,
        new ExecutionReport(
            "EX-1", new BigDecimal("150.00"), 100, 0, "PRIMARY", Instant.now(), null));

    var view = registry.view("b1").orElseThrow();
    assertThat(view.filledQuantity()).isEqualTo(100);
    assertThat(view.status()).isEqualTo(BasketStatus.FILLED);
  }

  @Test
  void onLegFill_partial_marksBasketPartiallyFilled() {
    var leg = trackedLeg("b1", 100);

    coordinator.onLegFillIfApplicable(
        leg,
        new ExecutionReport(
            "EX-1", new BigDecimal("150.00"), 40, 60, "PRIMARY", Instant.now(), null));

    var view = registry.view("b1").orElseThrow();
    assertThat(view.filledQuantity()).isEqualTo(40);
    assertThat(view.status()).isEqualTo(BasketStatus.PARTIALLY_FILLED);
  }

  @Test
  void onLegFill_noOpForOrderThatIsNotABasketLeg() {
    var untracked =
        new Order(
            new OrderSignal(
                "TSLA", Side.SELL, 10, OrderType.MARKET, null, null, "MANUAL", Instant.now()));

    coordinator.onLegFillIfApplicable(
        untracked,
        new ExecutionReport(
            "EX-Z", new BigDecimal("250.00"), 10, 0, "PRIMARY", Instant.now(), null));

    assertThat(registry.trackedBaskets()).isZero();
  }
}
