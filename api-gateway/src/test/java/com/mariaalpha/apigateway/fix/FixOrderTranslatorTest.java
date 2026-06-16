package com.mariaalpha.apigateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import quickfix.field.ClOrdID;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.StopPx;
import quickfix.field.Symbol;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

class FixOrderTranslatorTest {

  private NewOrderSingle order(char side, char ordType) {
    var nos =
        new NewOrderSingle(
            new ClOrdID("c1"),
            new Side(side),
            new TransactTime(LocalDateTime.now(ZoneOffset.UTC)),
            new OrdType(ordType));
    nos.set(new Symbol("AAPL"));
    nos.set(new OrderQty(100));
    return nos;
  }

  @Test
  void limitBuy_mapsAllFields() throws Exception {
    var nos = order('1', '2');
    nos.set(new Price(150.25));
    nos.set(new TimeInForce('0'));

    var sub = FixOrderTranslator.translate(nos);

    assertThat(sub.clOrdId()).isEqualTo("c1");
    assertThat(sub.symbol()).isEqualTo("AAPL");
    assertThat(sub.side()).isEqualTo("BUY");
    assertThat(sub.orderType()).isEqualTo("LIMIT");
    assertThat(sub.quantity()).isEqualTo(100);
    assertThat(sub.limitPrice()).isEqualByComparingTo("150.25");
    assertThat(sub.stopPrice()).isNull();
    assertThat(sub.timeInForce()).isEqualTo("DAY");
  }

  @Test
  void marketSell_noPriceNoTif() throws Exception {
    var sub = FixOrderTranslator.translate(order('2', '1'));

    assertThat(sub.side()).isEqualTo("SELL");
    assertThat(sub.orderType()).isEqualTo("MARKET");
    assertThat(sub.limitPrice()).isNull();
    assertThat(sub.timeInForce()).isNull();
  }

  @Test
  void stopOrder_mapsStopPx() throws Exception {
    var nos = order('1', '3');
    nos.set(new StopPx(149.00));

    var sub = FixOrderTranslator.translate(nos);

    assertThat(sub.orderType()).isEqualTo("STOP");
    assertThat(sub.stopPrice()).isEqualByComparingTo("149.00");
  }

  @Test
  void limitWithoutPrice_throws() {
    assertThatThrownBy(() -> FixOrderTranslator.translate(order('1', '2')))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Price");
  }

  @Test
  void stopWithoutStopPx_throws() {
    assertThatThrownBy(() -> FixOrderTranslator.translate(order('1', '3')))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("StopPx");
  }

  @Test
  void unsupportedSide_throws() {
    assertThatThrownBy(() -> FixOrderTranslator.translate(order('5', '1')))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Side");
  }

  @Test
  void unsupportedOrdType_throws() {
    assertThatThrownBy(() -> FixOrderTranslator.translate(order('1', '4')))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OrdType");
  }

  @Test
  void timeInForceMappings() throws Exception {
    assertThat(tif('1')).isEqualTo("GTC");
    assertThat(tif('3')).isEqualTo("IOC");
    assertThat(tif('4')).isEqualTo("FOK");
  }

  private String tif(char fixTif) throws Exception {
    var nos = order('1', '1');
    nos.set(new TimeInForce(fixTif));
    return FixOrderTranslator.translate(nos).timeInForce();
  }
}
