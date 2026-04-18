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

class MarketOrderHandlerTest {

  private MarketOrderHandler handler;

  @BeforeEach
  void setUp() {
    handler = new MarketOrderHandler();
  }

  @Test
  void supportedTypeIsMarket() {
    assertThat(handler.supportedType()).isEqualTo(OrderType.MARKET);
  }

  @Test
  void validateSucceeds() {
    var order = createOrder(100, OrderType.MARKET, null, null);
    var market = createMarketState("AAPL", "149.50", "150.50", "150.00");
    assertThat(handler.validate(order, market).valid()).isTrue();
  }

  @Test
  void validateFailsZeroQuantity() {
    var order = createOrder(0, OrderType.MARKET, null, null);
    var market = createMarketState("AAPL", "149.50", "150.50", "150.00");
    var result = handler.validate(order, market);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("Quantity must be positive");
  }

  @Test
  void validateFailsNoMarketData() {
    var order = createOrder(100, OrderType.MARKET, null, null);
    var result = handler.validate(order, null);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("No market data");
  }

  @Test
  void toExecutionInstructionSetsDay() {
    var order = createOrder(100, OrderType.MARKET, null, null);
    var instruction = handler.toExecutionInstruction(order);
    assertThat(instruction.timeInForce()).isEqualTo("day");
    assertThat(instruction.adjustedLimitPrice()).isNull();
  }

  private Order createOrder(int qty, OrderType type, BigDecimal limitPrice, BigDecimal stopPrice) {
    var signal =
        new OrderSignal("AAPL", Side.BUY, qty, type, limitPrice, stopPrice, "VWAP", Instant.now());
    return new Order(signal);
  }

  private MarketState createMarketState(String symbol, String bid, String ask, String last) {
    return new MarketState(
        symbol, new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(last), Instant.now());
  }
}
