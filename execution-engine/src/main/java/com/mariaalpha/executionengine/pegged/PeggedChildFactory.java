package com.mariaalpha.executionengine.pegged;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Builds the LIMIT child order that fronts a PEGGED parent at the venue.
 *
 * <p>The child inherits the parent's symbol, side, and remaining quantity; its limit price is the
 * freshly-computed pegged price. The {@code strategyName} on the child is the literal {@code
 * "PEGGED-CHILD"} so downstream TCA / metrics group child fills under the parent strategy. The
 * parent's {@code orderId} flows into the child as {@code parentOrderId}.
 */
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
