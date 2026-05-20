package com.mariaalpha.executionengine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IocOrderHandlerTest {

  private IocOrderHandler handler;

  @BeforeEach
  void setUp() {
    handler = new IocOrderHandler();
  }

  @Test
  void supportedTypeIsIoc() {
    assertThat(handler.supportedType()).isEqualTo(OrderType.IOC);
  }

  @Test
  void validateFailsZeroQuantity() {
    var order = createOrder(Side.BUY, 0, new BigDecimal("150.00"));
    var result = handler.validate(order, marketState("149.50", "150.50"));
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("Quantity must be positive");
  }

  @Test
  void validateFailsNullLimitPrice() {
    var order = createOrder(Side.BUY, 100, null);
    var result = handler.validate(order, marketState("149.50", "150.50"));
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("positive limit price");
  }

  @Test
  void validateFailsNoMarketData() {
    var order = createOrder(Side.BUY, 100, new BigDecimal("150.00"));
    var result = handler.validate(order, null);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("No market data");
  }

  @Test
  void validateFailsBuyBelowAsk() {
    var order = createOrder(Side.BUY, 100, new BigDecimal("149.00"));
    var result = handler.validate(order, marketState("149.50", "150.50"));
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("cancel with zero fills");
  }

  @Test
  void validateFailsSellAboveBid() {
    var order = createOrder(Side.SELL, 100, new BigDecimal("151.00"));
    var result = handler.validate(order, marketState("149.50", "150.50"));
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("cancel with zero fills");
  }

  @Test
  void validateSucceedsBuyAboveAsk() {
    var order = createOrder(Side.BUY, 100, new BigDecimal("151.00"));
    assertThat(handler.validate(order, marketState("149.50", "150.50")).valid()).isTrue();
  }

  @Test
  void toExecutionInstructionEmitsIocTif() {
    var order = createOrder(Side.BUY, 100, new BigDecimal("151.00"));
    var instr = handler.toExecutionInstruction(order);
    assertThat(instr.timeInForce()).isEqualTo(TimeInForce.IOC);
    assertThat(instr.adjustedLimitPrice()).isEqualByComparingTo("151.00");
    assertThat(instr.displayQuantity()).isNull();
  }

  private Order createOrder(Side side, int qty, BigDecimal limitPrice) {
    return new Order(
        new OrderSignal(
            "AAPL", side, qty, OrderType.IOC, limitPrice, null, "MANUAL", Instant.now()));
  }

  private MarketState marketState(String bid, String ask) {
    return new MarketState(
        "AAPL", new BigDecimal(bid), new BigDecimal(ask), new BigDecimal("150.00"), Instant.now());
  }
}
