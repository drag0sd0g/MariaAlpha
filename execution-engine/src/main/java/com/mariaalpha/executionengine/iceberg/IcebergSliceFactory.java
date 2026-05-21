package com.mariaalpha.executionengine.iceberg;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.TimeInForce;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Builds child LIMIT orders from an iceberg parent.
 *
 * <p>Children inherit the parent's symbol, side, and limit price. The {@code strategyName} on the
 * child is the literal {@code "ICEBERG-CHILD"} so downstream metrics / TCA reports can group child
 * fills under their parent strategy. The parent's {@code orderId} flows into the child as {@code
 * parentOrderId}.
 */
@Component
public class IcebergSliceFactory {

  public Order createChild(Order parent, int sliceQty, int sliceIndex) {
    var signal =
        new OrderSignal(
            parent.getSymbol(),
            parent.getSide(),
            sliceQty,
            OrderType.LIMIT,
            parent.getLimitPrice(),
            null,
            "ICEBERG-CHILD",
            Instant.now(),
            null,
            TimeInForce.DAY,
            parent.getOrderId());
    return new Order(signal);
  }
}
