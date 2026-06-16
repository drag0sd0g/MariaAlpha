package com.mariaalpha.executionengine.pegged;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class PeggedChildFactory {

  public Order createChild(Order parent, int remainingQuantity, BigDecimal peggedPrice) {
    var signal =
        new OrderSignal(
            parent.getSymbol(),
            parent.getSide(),
            remainingQuantity,
            OrderType.LIMIT,
            peggedPrice,
            null,
            "PEGGED-CHILD",
            Instant.now(),
            null,
            TimeInForce.DAY,
            parent.getOrderId(),
            null,
            null);
    return new Order(signal);
  }
}
