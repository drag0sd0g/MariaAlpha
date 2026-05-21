package com.mariaalpha.executionengine.handler;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import com.mariaalpha.executionengine.model.ValidationResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class IocOrderHandler implements OrderTypeHandler {
  @Override
  public OrderType supportedType() {
    return OrderType.IOC;
  }

  @Override
  public ValidationResult validate(Order order, MarketState marketState) {
    if (order.getQuantity() <= 0) {
      return ValidationResult.fail("Quantity must be positive");
    }

    if (order.getLimitPrice() == null || order.getLimitPrice().compareTo(BigDecimal.ZERO) <= 0) {
      return ValidationResult.fail("IOC order requires a positive limit price");
    }

    if (marketState == null) {
      return ValidationResult.fail("No market data available for " + order.getSymbol());
    }

    // Marketability check: an IOC that cannot fill at submission time would be cancelled
    // for zero fills. We reject pre-flight so the user sees a clear validation error
    // rather than an opaque "ioc-residual-cancel" terminal event.
    if (order.getSide() == Side.BUY
        && order.getLimitPrice().compareTo(marketState.askPrice()) < 0) {
      return ValidationResult.fail(
          "IOC BUY limit "
              + order.getLimitPrice()
              + "is below current ask "
              + marketState.askPrice()
              + " - order would cancel with zero fills");
    }
    if (order.getSide() == Side.SELL
        && order.getLimitPrice().compareTo(marketState.bidPrice()) > 0) {
      return ValidationResult.fail(
          "IOC SELL limit "
              + order.getLimitPrice()
              + "is above current bid "
              + marketState.bidPrice()
              + " - order would cancel with zero fills");
    }

    return ValidationResult.ok();
  }

  @Override
  public ExecutionInstruction toExecutionInstruction(Order order) {
    return new ExecutionInstruction(order, TimeInForce.IOC, order.getLimitPrice());
  }
}
