package com.mariaalpha.executionengine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.pegged.PegType;
import com.mariaalpha.executionengine.pegged.PeggedConfig;
import com.mariaalpha.executionengine.pegged.PeggedPriceCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PeggedOrderHandlerTest {

  private static final PeggedConfig CONFIG = new PeggedConfig(5, 100);

  private final PeggedOrderHandler handler =
      new PeggedOrderHandler(new PeggedPriceCalculator(), CONFIG);

  private static Order order(
      Side side, int quantity, PegType pegType, Integer offsetBps, BigDecimal priceCap) {
    return new Order(
        new OrderSignal(
            "AAPL",
            side,
            quantity,
            OrderType.PEGGED,
            priceCap,
            null,
            "MANUAL",
            Instant.EPOCH,
            null,
            null,
            null,
            pegType,
            offsetBps));
  }

  private static MarketState book(String bid, String ask) {
    return new MarketState(
        "AAPL", new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(bid), Instant.EPOCH);
  }

  @Test
  void acceptsValidMidpointBuy() {
    var result =
        handler.validate(order(Side.BUY, 100, PegType.MIDPOINT, 0, null), book("100", "100.20"));
    assertThat(result.valid()).isTrue();
  }

  @Test
  void rejectsZeroQuantity() {
    var result =
        handler.validate(order(Side.BUY, 0, PegType.MIDPOINT, 0, null), book("100", "100.20"));
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("Quantity");
  }

  @Test
  void rejectsMissingPegType() {
    var result = handler.validate(order(Side.BUY, 100, null, 0, null), book("100", "100.20"));
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("pegType");
  }

  @Test
  void rejectsOffsetAboveConfiguredMax() {
    var result =
        handler.validate(order(Side.BUY, 100, PegType.MIDPOINT, 200, null), book("100", "100.20"));
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("pegOffsetBps");
  }

  @Test
  void rejectsNullMarketState() {
    var result = handler.validate(order(Side.BUY, 100, PegType.MIDPOINT, 0, null), null);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("market data");
  }

  @Test
  void rejectsBuyPriceCapTooLow() {
    var result =
        handler.validate(
            order(Side.BUY, 100, PegType.MIDPOINT, 0, new BigDecimal("30.00")),
            book("100", "100.20"));
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("priceCap");
  }

  @Test
  void rejectsSellPriceCapTooHigh() {
    var result =
        handler.validate(
            order(Side.SELL, 100, PegType.MIDPOINT, 0, new BigDecimal("300.00")),
            book("100", "100.20"));
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("priceCap");
  }

  @Test
  void rejectsOneSidedBookForMidpoint() {
    var oneSided = new MarketState("AAPL", null, new BigDecimal("100.20"), null, Instant.EPOCH);
    var result = handler.validate(order(Side.BUY, 100, PegType.MIDPOINT, 0, null), oneSided);
    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).contains("reference price");
  }

  @Test
  void supportedTypeIsPegged() {
    assertThat(handler.supportedType()).isEqualTo(OrderType.PEGGED);
  }
}
