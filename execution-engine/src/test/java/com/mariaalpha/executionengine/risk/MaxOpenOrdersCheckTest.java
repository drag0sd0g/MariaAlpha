package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MaxOpenOrdersCheckTest {

  private OrderLifecycleManager lifecycleManager;
  private MaxOpenOrdersCheck check;

  @BeforeEach
  void setUp() {
    lifecycleManager = mock(OrderLifecycleManager.class);
    var config = new RiskLimitsConfig(100_000, 500_000, 2_000_000, 50, 25_000);
    check = new MaxOpenOrdersCheck(config, lifecycleManager);
  }

  @Test
  void passesWhenUnderLimit() {
    when(lifecycleManager.getOpenOrderCount()).thenReturn(30);
    var order = createOrder();
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void failsAtLimit() {
    when(lifecycleManager.getOpenOrderCount()).thenReturn(50);
    var order = createOrder();
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.checkName()).isEqualTo("MaxOpenOrders");
  }

  private Order createOrder() {
    return new Order(
        new OrderSignal(
            "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
