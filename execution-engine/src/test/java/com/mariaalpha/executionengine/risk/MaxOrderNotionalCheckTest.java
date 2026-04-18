package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MaxOrderNotionalCheckTest {

  private MarketStateTracker marketStateTracker;
  private MaxOrderNotionalCheck check;

  @BeforeEach
  void setUp() {
    marketStateTracker = mock(MarketStateTracker.class);
    var config = new RiskLimitsConfig(100_000, 500_000, 2_000_000, 50, 25_000);
    check = new MaxOrderNotionalCheck(config, marketStateTracker);
  }

  @Test
  void passesUnderLimit() {
    when(marketStateTracker.getMarketState("AAPL"))
        .thenReturn(
            new MarketState(
                "AAPL",
                new BigDecimal("149"),
                new BigDecimal("151"),
                new BigDecimal("150"),
                Instant.now()));
    var order = createOrder("AAPL", 333); // 333 * $150 = $49,950
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void failsOverLimit() {
    when(marketStateTracker.getMarketState("AAPL"))
        .thenReturn(
            new MarketState(
                "AAPL",
                new BigDecimal("149"),
                new BigDecimal("151"),
                new BigDecimal("150"),
                Instant.now()));
    var order = createOrder("AAPL", 1000); // 1000 * $150 = $150,000
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.checkName()).isEqualTo("MaxOrderNotional");
  }

  @Test
  void failsNoMarketData() {
    when(marketStateTracker.getMarketState("AAPL")).thenReturn(null);
    var order = createOrder("AAPL", 100);
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("Market data unavailable");
  }

  private Order createOrder(String symbol, int qty) {
    return new Order(
        new OrderSignal(
            symbol, Side.BUY, qty, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
