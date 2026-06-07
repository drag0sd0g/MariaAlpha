package com.mariaalpha.executionengine.pegged;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PeggedPriceCalculatorTest {

  private final PeggedPriceCalculator calculator = new PeggedPriceCalculator();

  private static MarketState book(String bid, String ask) {
    return new MarketState(
        "AAPL", new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(bid), Instant.EPOCH);
  }

  @Test
  void midpointAveragesBidAndAsk() {
    var ref = calculator.referencePrice(PegType.MIDPOINT, Side.BUY, book("100.00", "100.20"));
    assertThat(ref.doubleValue()).isCloseTo(100.10, within(1e-6));
  }

  @Test
  void primaryBuyPegsToBidAndSellPegsToAsk() {
    var state = book("100.00", "100.20");
    assertThat(calculator.referencePrice(PegType.PRIMARY, Side.BUY, state))
        .isEqualByComparingTo("100.00");
    assertThat(calculator.referencePrice(PegType.PRIMARY, Side.SELL, state))
        .isEqualByComparingTo("100.20");
  }

  @Test
  void marketBuyPegsToAskAndSellPegsToBid() {
    var state = book("100.00", "100.20");
    assertThat(calculator.referencePrice(PegType.MARKET, Side.BUY, state))
        .isEqualByComparingTo("100.20");
    assertThat(calculator.referencePrice(PegType.MARKET, Side.SELL, state))
        .isEqualByComparingTo("100.00");
  }

  @Test
  void midpointReturnsNullForOneSidedBook() {
    var oneSided = new MarketState("AAPL", null, new BigDecimal("100.20"), null, Instant.EPOCH);
    assertThat(calculator.referencePrice(PegType.MIDPOINT, Side.BUY, oneSided)).isNull();
  }

  @Test
  void zeroOffsetEqualsReference() {
    var price = calculator.peggedPrice(new BigDecimal("100.10"), Side.BUY, 0, null);
    assertThat(price.doubleValue()).isCloseTo(100.10, within(1e-6));
  }

  @Test
  void positiveOffsetMovesBuyUpAndSellDown() {
    // BUY @ +5 bps → 100.10 × 1.0005 = 100.150
    var buy = calculator.peggedPrice(new BigDecimal("100.10"), Side.BUY, 5, null);
    assertThat(buy.doubleValue()).isCloseTo(100.150, within(1e-3));
    // SELL @ +5 bps → 100.10 × 0.9995 = 100.050
    var sell = calculator.peggedPrice(new BigDecimal("100.10"), Side.SELL, 5, null);
    assertThat(sell.doubleValue()).isCloseTo(100.050, within(1e-3));
  }

  @Test
  void negativeOffsetMovesBuyDownAndSellUp() {
    var buy = calculator.peggedPrice(new BigDecimal("100.10"), Side.BUY, -10, null);
    assertThat(buy.doubleValue()).isCloseTo(100.000, within(1e-3));
    var sell = calculator.peggedPrice(new BigDecimal("100.10"), Side.SELL, -10, null);
    assertThat(sell.doubleValue()).isCloseTo(100.200, within(1e-3));
  }

  @Test
  void priceCapClampsBuyAboveCap() {
    // BUY @ +500 bps on 100 → 105.00; cap at 100.50 → must clamp.
    var price =
        calculator.peggedPrice(new BigDecimal("100.00"), Side.BUY, 500, new BigDecimal("100.50"));
    assertThat(price).isEqualByComparingTo("100.5000");
  }

  @Test
  void priceCapClampsSellBelowCap() {
    var price =
        calculator.peggedPrice(new BigDecimal("100.00"), Side.SELL, 500, new BigDecimal("99.50"));
    assertThat(price).isEqualByComparingTo("99.5000");
  }

  @Test
  void priceCapDoesNotInterfereWhenWithinBound() {
    var price =
        calculator.peggedPrice(new BigDecimal("100.00"), Side.BUY, 1, new BigDecimal("110.00"));
    assertThat(price.doubleValue()).isCloseTo(100.010, within(1e-3));
  }

  @Test
  void shouldRepegWhenMoveExceedsThreshold() {
    assertThat(calculator.shouldRepeg(new BigDecimal("100.00"), new BigDecimal("100.06"), 5))
        .isTrue();
  }

  @Test
  void shouldNotRepegWhenMoveBelowThreshold() {
    assertThat(calculator.shouldRepeg(new BigDecimal("100.00"), new BigDecimal("100.03"), 5))
        .isFalse();
  }

  @Test
  void shouldNotRepegOnIdenticalPrice() {
    assertThat(calculator.shouldRepeg(new BigDecimal("100.00"), new BigDecimal("100.00"), 5))
        .isFalse();
  }

  @Test
  void shouldRepegFromNullToReal() {
    assertThat(calculator.shouldRepeg(null, new BigDecimal("100.00"), 5)).isTrue();
    assertThat(calculator.shouldRepeg(new BigDecimal("100.00"), null, 5)).isFalse();
  }
}
