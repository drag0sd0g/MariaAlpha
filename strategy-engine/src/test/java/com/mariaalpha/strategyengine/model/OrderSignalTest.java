package com.mariaalpha.strategyengine.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderSignalTest {

  @Test
  void recordEquality() {
    var ts = Instant.parse("2026-03-24T14:30:00Z");
    var a =
        new OrderSignal(
            "AAPL", Side.BUY, 100, OrderType.LIMIT, new BigDecimal("178.50"), "VWAP", ts);
    var b =
        new OrderSignal(
            "AAPL", Side.BUY, 100, OrderType.LIMIT, new BigDecimal("178.50"), "VWAP", ts);
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void nullLimitPriceForMarketOrder() {
    var signal =
        new OrderSignal("AAPL", Side.BUY, 500, OrderType.MARKET, null, "VWAP", Instant.now());
    assertThat(signal.orderType()).isEqualTo(OrderType.MARKET);
    assertThat(signal.limitPrice()).isNull();
  }
}
