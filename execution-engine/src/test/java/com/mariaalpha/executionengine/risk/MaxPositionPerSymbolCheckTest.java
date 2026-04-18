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
import com.mariaalpha.executionengine.service.PositionTracker;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MaxPositionPerSymbolCheckTest {

  private MarketStateTracker marketStateTracker;
  private PositionTracker positionTracker;
  private MaxPositionPerSymbolCheck check;

  @BeforeEach
  void setUp() {
    marketStateTracker = mock(MarketStateTracker.class);
    positionTracker = mock(PositionTracker.class);
    var config = new RiskLimitsConfig(100_000, 500_000, 2_000_000, 50, 25_000);
    check = new MaxPositionPerSymbolCheck(config, marketStateTracker, positionTracker);
  }

  @Test
  void passesWhenBelowLimit() {
    when(marketStateTracker.getMarketState("AAPL"))
        .thenReturn(
            new MarketState(
                "AAPL",
                new BigDecimal("149"),
                new BigDecimal("151"),
                new BigDecimal("150"),
                Instant.now()));
    when(positionTracker.getPositionNotional("AAPL")).thenReturn(new BigDecimal("200000"));
    var order = createOrder("AAPL", 666); // 666 * $150 = $99,900 → total $299,900
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void failsWhenAboveLimit() {
    when(marketStateTracker.getMarketState("AAPL"))
        .thenReturn(
            new MarketState(
                "AAPL",
                new BigDecimal("149"),
                new BigDecimal("151"),
                new BigDecimal("150"),
                Instant.now()));
    when(positionTracker.getPositionNotional("AAPL")).thenReturn(new BigDecimal("400000"));
    var order = createOrder("AAPL", 1000); // 1000 * $150 = $150K → total $550K > $500K
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.checkName()).isEqualTo("MaxPositionPerSymbol");
  }

  private Order createOrder(String symbol, int qty) {
    return new Order(
        new OrderSignal(
            symbol, Side.BUY, qty, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
