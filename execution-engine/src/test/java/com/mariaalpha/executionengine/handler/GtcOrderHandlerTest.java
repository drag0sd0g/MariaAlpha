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

class GtcOrderHandlerTest {

  private GtcOrderHandler handler;

  @BeforeEach
  void setUp() {
    handler = new GtcOrderHandler();
  }

  @Test
  void supportedTypeIsGtc() {
    assertThat(handler.supportedType()).isEqualTo(OrderType.GTC);
  }

  @Test
  void validateFailsZeroQuantity() {
    var order = createOrder(0, new BigDecimal("150.00"));
    var result = handler.validate(order, marketState());
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("Quantity must be positive");
  }

  @Test
  void validateFailsNullLimitPrice() {
    var order = createOrder(100, null);
    var result = handler.validate(order, marketState());
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("positive limit price");
  }

  @Test
  void validateFailsNoMarketData() {
    var order = createOrder(100, new BigDecimal("150.00"));
    var result = handler.validate(order, null);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("No market data");
  }

  @Test
  void validateSucceeds() {
    var order = createOrder(100, new BigDecimal("150.00"));
    assertThat(handler.validate(order, marketState()).valid()).isTrue();
  }

  @Test
  void toExecutionInstructionEmitsGtcTif() {
    var order = createOrder(100, new BigDecimal("150.00"));
    var instr = handler.toExecutionInstruction(order);
    assertThat(instr.timeInForce()).isEqualTo(TimeInForce.GTC);
    assertThat(instr.adjustedLimitPrice()).isEqualByComparingTo("150.00");
    assertThat(instr.displayQuantity()).isNull();
  }

  private Order createOrder(int qty, BigDecimal limitPrice) {
    return new Order(
        new OrderSignal(
            "AAPL", Side.BUY, qty, OrderType.GTC, limitPrice, null, "MANUAL", Instant.now()));
  }

  private MarketState marketState() {
    return new MarketState(
        "AAPL",
        new BigDecimal("149.50"),
        new BigDecimal("150.50"),
        new BigDecimal("150.00"),
        Instant.now());
  }
}
