package com.mariaalpha.executionengine.handler;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.ValidationResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class LimitOrderHandler implements OrderTypeHandler {

  @Override
  public OrderType supportedType() {
    return OrderType.LIMIT;
  }

  @Override
  public ValidationResult validate(Order order, MarketState marketState) {
    if (order.getQuantity() <= 0) {
      return ValidationResult.fail("Quantity must be positive");
    }
    if (order.getLimitPrice() == null || order.getLimitPrice().compareTo(BigDecimal.ZERO) <= 0) {
      return ValidationResult.fail("Limit order requires a positive limit price");
    }
    if (marketState == null) {
      return ValidationResult.fail("No market data available for " + order.getSymbol());
    }
    return ValidationResult.ok();
  }

  @Override
  public ExecutionInstruction toExecutionInstruction(Order order) {
    return new ExecutionInstruction(order, "day", order.getLimitPrice());
  }
}
