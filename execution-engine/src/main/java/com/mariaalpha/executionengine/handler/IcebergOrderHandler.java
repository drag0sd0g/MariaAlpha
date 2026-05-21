package com.mariaalpha.executionengine.handler;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.TimeInForce;
import com.mariaalpha.executionengine.model.ValidationResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Validates ICEBERG <em>parent</em> orders. The execution-engine never submits an iceberg parent
 * directly to a venue — the parent is captured by {@link
 * com.mariaalpha.executionengine.iceberg.IcebergCoordinator}, which produces {@code
 * displayQuantity}-sized LIMIT child orders that flow through {@link LimitOrderHandler}. {@link
 * #toExecutionInstruction} is therefore only used to surface validation/preview output via the
 * manual order REST endpoint; the actual venue submission path bypasses it.
 */
@Component
public class IcebergOrderHandler implements OrderTypeHandler {

  @Override
  public OrderType supportedType() {
    return OrderType.ICEBERG;
  }

  @Override
  public ValidationResult validate(Order order, MarketState marketState) {
    if (order.getQuantity() <= 0) {
      return ValidationResult.fail("Quantity must be positive");
    }
    if (order.getLimitPrice() == null || order.getLimitPrice().compareTo(BigDecimal.ZERO) <= 0) {
      return ValidationResult.fail("ICEBERG order requires a positive limit price");
    }
    if (order.getDisplayQuantity() == null || order.getDisplayQuantity() <= 0) {
      return ValidationResult.fail("ICEBERG order requires a positive displayQuantity");
    }
    if (order.getDisplayQuantity() >= order.getQuantity()) {
      return ValidationResult.fail(
          "ICEBERG displayQuantity ("
              + order.getDisplayQuantity()
              + ") must be strictly less than quantity ("
              + order.getQuantity()
              + ")");
    }
    if (marketState == null) {
      return ValidationResult.fail("No market data available for " + order.getSymbol());
    }
    return ValidationResult.ok();
  }

  @Override
  public ExecutionInstruction toExecutionInstruction(Order order) {
    return new ExecutionInstruction(
        order, TimeInForce.DAY, order.getLimitPrice(), order.getDisplayQuantity());
  }
}
