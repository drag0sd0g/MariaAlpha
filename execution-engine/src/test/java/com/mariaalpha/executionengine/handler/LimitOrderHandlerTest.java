package com.mariaalpha.executionengine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LimitOrderHandlerTest {

  private LimitOrderHandler handler;

  @BeforeEach
  void setUp() {
    handler = new LimitOrderHandler();
  }

  @Test
  void validateFailsNullLimitPrice() {
    var order = createOrder(100, null);
    var market = createMarketState();
    var result = handler.validate(order, market);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("positive limit price");
  }

  @Test
  void validateFailsZeroLimitPrice() {
    var order = createOrder(100, BigDecimal.ZERO);
    var market = createMarketState();
    var result = handler.validate(order, market);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("positive limit price");
  }

  @Test
  void validateSucceeds() {
    var order = createOrder(100, new BigDecimal("150.00"));
    var market = createMarketState();
    assertThat(handler.validate(order, market).valid()).isTrue();
  }

  @Test
  void toExecutionInstructionUsesLimitPrice() {
    var limitPrice = new BigDecimal("150.00");
    var order = createOrder(100, limitPrice);
    var instruction = handler.toExecutionInstruction(order);
    assertThat(instruction.adjustedLimitPrice()).isEqualByComparingTo(limitPrice);
    assertThat(instruction.timeInForce()).isEqualTo("day");
  }

  private Order createOrder(int qty, BigDecimal limitPrice) {
    var signal =
        new OrderSignal(
            "AAPL", Side.BUY, qty, OrderType.LIMIT, limitPrice, null, "VWAP", Instant.now());
    return new Order(signal);
  }

  private MarketState createMarketState() {
    return new MarketState(
        "AAPL",
        new BigDecimal("149.50"),
        new BigDecimal("150.50"),
        new BigDecimal("150.00"),
        Instant.now());
  }
}
