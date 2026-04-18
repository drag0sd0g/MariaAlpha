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

class StopOrderHandlerTest {

  private StopOrderHandler handler;

  @BeforeEach
  void setUp() {
    handler = new StopOrderHandler();
  }

  @Test
  void validateFailsNullStopPrice() {
    var order = createOrder(Side.BUY, null);
    var market = createMarketState("149.50", "150.50");
    var result = handler.validate(order, market);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("positive stop price");
  }

  @Test
  void validateFailsBuyStopBelowAsk() {
    var order = createOrder(Side.BUY, new BigDecimal("149.00"));
    var market = createMarketState("149.50", "150.50");
    var result = handler.validate(order, market);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("above current ask");
  }

  @Test
  void validateFailsSellStopAboveBid() {
    var order = createOrder(Side.SELL, new BigDecimal("151.00"));
    var market = createMarketState("149.50", "150.50");
    var result = handler.validate(order, market);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("below current bid");
  }

  @Test
  void validateSucceedsBuyStopAboveAsk() {
    var order = createOrder(Side.BUY, new BigDecimal("152.00"));
    var market = createMarketState("149.50", "150.50");
    assertThat(handler.validate(order, market).valid()).isTrue();
  }

  @Test
  void validateSucceedsSellStopBelowBid() {
    var order = createOrder(Side.SELL, new BigDecimal("148.00"));
    var market = createMarketState("149.50", "150.50");
    assertThat(handler.validate(order, market).valid()).isTrue();
  }

  private Order createOrder(Side side, BigDecimal stopPrice) {
    var signal =
        new OrderSignal("AAPL", side, 100, OrderType.STOP, null, stopPrice, "VWAP", Instant.now());
    return new Order(signal);
  }

  private MarketState createMarketState(String bid, String ask) {
    return new MarketState(
        "AAPL", new BigDecimal(bid), new BigDecimal(ask), new BigDecimal("150.00"), Instant.now());
  }
}
