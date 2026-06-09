package com.mariaalpha.strategyengine.algo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.strategyengine.model.Side;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlgoOrderRegistryTest {

  private AlgoOrderRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new AlgoOrderRegistry();
  }

  @Test
  void findReturnsRegisteredOrder() {
    var order = activeOrder("AAPL");
    registry.register(order);
    assertThat(registry.find(order.algoOrderId())).contains(order);
  }

  @Test
  void findReturnsEmptyForUnknownId() {
    assertThat(registry.find(UUID.randomUUID())).isEmpty();
  }

  @Test
  void activeForSymbolFiltersOutCancelledOrders() {
    var active = activeOrder("AAPL");
    var cancelled = activeOrder("AAPL").withStatus(AlgoOrder.Status.CANCELLED);
    registry.register(active);
    registry.register(cancelled);
    assertThat(registry.activeForSymbol("AAPL")).containsExactly(active);
  }

  @Test
  void activeForSymbolIsCaseInsensitive() {
    var order = activeOrder("aapl");
    registry.register(order);
    assertThat(registry.activeForSymbol("AAPL")).containsExactly(order);
  }

  @Test
  void transitionUpdatesStatusAndStoresBack() {
    var order = activeOrder("AAPL");
    registry.register(order);
    var updated = registry.transition(order.algoOrderId(), AlgoOrder.Status.CANCELLED);
    assertThat(updated).isPresent();
    assertThat(updated.get().status()).isEqualTo(AlgoOrder.Status.CANCELLED);
    assertThat(registry.find(order.algoOrderId()).get().status())
        .isEqualTo(AlgoOrder.Status.CANCELLED);
  }

  @Test
  void transitionReturnsEmptyForUnknownId() {
    assertThat(registry.transition(UUID.randomUUID(), AlgoOrder.Status.CANCELLED)).isEmpty();
  }

  private static AlgoOrder activeOrder(String symbol) {
    var now = Instant.now();
    return new AlgoOrder(
        UUID.randomUUID(),
        symbol,
        Side.BUY,
        100,
        "VWAP",
        Map.of(),
        AlgoOrder.Status.ACTIVE,
        now,
        now);
  }
}
