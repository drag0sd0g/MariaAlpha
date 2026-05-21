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

class IcebergOrderHandlerTest {

  private IcebergOrderHandler handler;

  @BeforeEach
  void setUp() {
    handler = new IcebergOrderHandler();
  }

  @Test
  void supportedTypeIsIceberg() {
    assertThat(handler.supportedType()).isEqualTo(OrderType.ICEBERG);
  }

  @Test
  void validateFailsZeroQuantity() {
    var order = createOrder(0, 1, new BigDecimal("150.00"));
    var result = handler.validate(order, marketState());
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("Quantity must be positive");
  }

  @Test
  void validateFailsNullLimitPrice() {
    var order = createOrder(1000, 100, null);
    var result = handler.validate(order, marketState());
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("positive limit price");
  }

  @Test
  void validateFailsNullDisplayQuantity() {
    var order = createOrder(1000, null, new BigDecimal("150.00"));
    var result = handler.validate(order, marketState());
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("positive displayQuantity");
  }

  @Test
  void validateFailsZeroDisplayQuantity() {
    var order = createOrder(1000, 0, new BigDecimal("150.00"));
    var result = handler.validate(order, marketState());
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("positive displayQuantity");
  }

  @Test
  void validateFailsDisplayQuantityEqualToQuantity() {
    var order = createOrder(1000, 1000, new BigDecimal("150.00"));
    var result = handler.validate(order, marketState());
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("must be strictly less than quantity");
  }

  @Test
  void validateFailsDisplayQuantityGreaterThanQuantity() {
    var order = createOrder(1000, 1500, new BigDecimal("150.00"));
    var result = handler.validate(order, marketState());
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("must be strictly less than quantity");
  }

  @Test
  void validateFailsNoMarketData() {
    var order = createOrder(1000, 100, new BigDecimal("150.00"));
    var result = handler.validate(order, null);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("No market data");
  }

  @Test
  void validateSucceeds() {
    var order = createOrder(1000, 100, new BigDecimal("150.00"));
    assertThat(handler.validate(order, marketState()).valid()).isTrue();
  }

  @Test
  void toExecutionInstructionPopulatesDisplayQuantity() {
    var order = createOrder(1000, 200, new BigDecimal("150.00"));
    var instr = handler.toExecutionInstruction(order);
    assertThat(instr.timeInForce()).isEqualTo(TimeInForce.DAY);
    assertThat(instr.adjustedLimitPrice()).isEqualByComparingTo("150.00");
    assertThat(instr.displayQuantity()).isEqualTo(200);
  }

  private Order createOrder(int qty, Integer displayQty, BigDecimal limitPrice) {
    var signal =
        new OrderSignal(
            "AAPL",
            Side.BUY,
            qty,
            OrderType.ICEBERG,
            limitPrice,
            null,
            "MANUAL",
            Instant.now(),
            displayQty,
            TimeInForce.DAY,
            null);
    return new Order(signal);
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
