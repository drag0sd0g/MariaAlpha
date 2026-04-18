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

class MaxPortfolioExposureCheckTest {

  private MarketStateTracker marketStateTracker;
  private PositionTracker positionTracker;
  private MaxPortfolioExposureCheck check;

  @BeforeEach
  void setUp() {
    marketStateTracker = mock(MarketStateTracker.class);
    positionTracker = mock(PositionTracker.class);
    var config = new RiskLimitsConfig(100_000, 500_000, 2_000_000, 50, 25_000);
    check = new MaxPortfolioExposureCheck(config, marketStateTracker, positionTracker);
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
    when(positionTracker.getTotalGrossExposure()).thenReturn(new BigDecimal("1800000"));
    var order = createOrder("AAPL", 1000); // $150K → total $1.95M < $2M
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
    when(positionTracker.getTotalGrossExposure()).thenReturn(new BigDecimal("1900000"));
    var order = createOrder("AAPL", 1000); // $150K → total $2.05M > $2M
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.checkName()).isEqualTo("MaxPortfolioExposure");
  }

  private Order createOrder(String symbol, int qty) {
    return new Order(
        new OrderSignal(
            symbol, Side.BUY, qty, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
