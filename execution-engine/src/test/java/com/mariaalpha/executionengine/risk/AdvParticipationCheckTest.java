package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.config.SymbolReferenceConfig;
import com.mariaalpha.executionengine.config.SymbolReferenceConfig.SymbolRef;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdvParticipationCheckTest {

  private AdvParticipationCheck check;

  @BeforeEach
  void setUp() {
    var refData = loadRefData();
    var config =
        new RiskLimitsConfig(100_000, 500_000, 2_000_000, 50, 25_000, Map.of(), 0L, 0L, 0.10);
    check = new AdvParticipationCheck(config, refData);
  }

  @Test
  void passesWhenOrderIsSmallFractionOfAdv() {
    // AAPL ADV = 60M; 1M shares = 1.67% ≪ 10%.
    var order = order("AAPL", 1_000_000);
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void failsWhenOrderExceedsParticipationLimit() {
    // AAPL ADV = 60M; 7M shares = 11.67% > 10%.
    var order = order("AAPL", 7_000_000);
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("11.67%").contains("60000000");
  }

  @Test
  void passesAtExactLimit() {
    // AAPL ADV = 60M; 6M = 10% exactly → passes (strict >).
    var order = order("AAPL", 6_000_000);
    assertThat(check.check(order).passed()).isTrue();
  }

  @Test
  void rejectsWhenAdvUnavailable() {
    var order = order("ZZZZ", 100); // ZZZZ unmapped → ADV defaults to 0
    var result = check.check(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("ADV unavailable");
  }

  @Test
  void disabledWhenParticipationLimitIsZero() {
    var refData = loadRefData();
    var disabledConfig =
        new RiskLimitsConfig(100_000, 500_000, 2_000_000, 50, 25_000, Map.of(), 0L, 0L, 0.0);
    var disabled = new AdvParticipationCheck(disabledConfig, refData);
    var huge = order("AAPL", 60_000_000);
    assertThat(disabled.check(huge).passed()).isTrue();
  }

  private static SymbolReferenceData loadRefData() {
    var cfg =
        new SymbolReferenceConfig(
            List.of(new SymbolRef("AAPL", "TECH", 1.20, 60_000_000L)),
            new SymbolRef("*", "UNKNOWN", 1.0, 0L));
    var data = new SymbolReferenceData(cfg);
    data.load();
    return data;
  }

  private static Order order(String symbol, int qty) {
    return new Order(
        new OrderSignal(
            symbol, Side.BUY, qty, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
