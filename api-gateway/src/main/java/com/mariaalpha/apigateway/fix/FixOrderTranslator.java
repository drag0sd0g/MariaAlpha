package com.mariaalpha.apigateway.fix;

import java.math.BigDecimal;
import quickfix.FieldNotFound;
import quickfix.fix44.NewOrderSingle;

public final class FixOrderTranslator {

  private FixOrderTranslator() {}

  public static FixOrderSubmission translate(NewOrderSingle order) throws FieldNotFound {
    var clOrdId = order.getClOrdID().getValue();
    var symbol = order.getSymbol().getValue();
    var side = mapSide(order.getSide().getValue());
    var orderType = mapOrdType(order.getOrdType().getValue());
    var quantity = (int) order.getOrderQty().getValue();
    if (quantity <= 0) {
      throw new IllegalArgumentException("OrderQty must be positive");
    }

    BigDecimal limitPrice = null;
    if ("LIMIT".equals(orderType)) {
      if (!order.isSetPrice()) {
        throw new IllegalArgumentException("LIMIT order requires Price (44)");
      }
      limitPrice = BigDecimal.valueOf(order.getPrice().getValue());
    }

    BigDecimal stopPrice = null;
    if ("STOP".equals(orderType)) {
      if (!order.isSetStopPx()) {
        throw new IllegalArgumentException("STOP order requires StopPx (99)");
      }
      stopPrice = BigDecimal.valueOf(order.getStopPx().getValue());
    }

    String tif = order.isSetTimeInForce() ? mapTif(order.getTimeInForce().getValue()) : null;

    return new FixOrderSubmission(
        clOrdId, symbol, side, orderType, quantity, limitPrice, stopPrice, tif);
  }

  static String mapSide(char fixSide) {
    return switch (fixSide) {
      case '1' -> "BUY";
      case '2' -> "SELL";
      default -> throw new IllegalArgumentException("Unsupported FIX Side (54): " + fixSide);
    };
  }

  static String mapOrdType(char fixOrdType) {
    return switch (fixOrdType) {
      case '1' -> "MARKET";
      case '2' -> "LIMIT";
      case '3' -> "STOP";
      default -> throw new IllegalArgumentException("Unsupported FIX OrdType (40): " + fixOrdType);
    };
  }

  static String mapTif(char fixTif) {
    return switch (fixTif) {
      case '0' -> "DAY";
      case '1' -> "GTC";
      case '3' -> "IOC";
      case '4' -> "FOK";
      default -> throw new IllegalArgumentException("Unsupported FIX TimeInForce (59): " + fixTif);
    };
  }
}
