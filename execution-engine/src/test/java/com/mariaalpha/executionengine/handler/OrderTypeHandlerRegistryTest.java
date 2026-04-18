package com.mariaalpha.executionengine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.OrderType;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTypeHandlerRegistryTest {

  @Test
  void registersAllDiscoveredHandlers() {
    var registry =
        new OrderTypeHandlerRegistry(
            List.of(new MarketOrderHandler(), new LimitOrderHandler(), new StopOrderHandler()));
    assertThat(registry.getHandler(OrderType.MARKET)).isPresent();
    assertThat(registry.getHandler(OrderType.LIMIT)).isPresent();
    assertThat(registry.getHandler(OrderType.STOP)).isPresent();
  }

  @Test
  void getHandlerReturnsCorrectHandler() {
    var marketHandler = new MarketOrderHandler();
    var registry = new OrderTypeHandlerRegistry(List.of(marketHandler));
    assertThat(registry.getHandler(OrderType.MARKET)).contains(marketHandler);
  }

  @Test
  void getHandlerReturnsEmptyForUnknown() {
    var registry = new OrderTypeHandlerRegistry(List.of());
    assertThat(registry.getHandler(OrderType.MARKET)).isEmpty();
  }
}
