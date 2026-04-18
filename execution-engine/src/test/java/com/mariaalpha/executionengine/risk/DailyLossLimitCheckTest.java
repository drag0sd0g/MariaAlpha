package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DailyLossLimitCheckTest {

  private DailyLossMonitor monitor;
  private DailyLossLimitCheck check;

  @BeforeEach
  void setUp() {
    monitor = mock(DailyLossMonitor.class);
    check = new DailyLossLimitCheck(monitor);
  }

  @Test
  void passesWhenNotHalted() {
    when(monitor.isTradingHalted()).thenReturn(false);
    var order = createOrder();
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void failsWhenHalted() {
    when(monitor.isTradingHalted()).thenReturn(true);
    when(monitor.getDailyPnl()).thenReturn(new BigDecimal("-30000"));
    var order = createOrder();
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.checkName()).isEqualTo("DailyLossLimit");
    assertThat(result.reason()).contains("Trading halted");
  }

  private Order createOrder() {
    return new Order(
        new OrderSignal(
            "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
