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
public class FokOrderHandler implements OrderTypeHandler {
  @Override
  public OrderType supportedType() {
    return OrderType.FOK;
  }

  @Override
  public ValidationResult validate(Order order, MarketState marketState) {
    if (order.getQuantity() <= 0) {
      return ValidationResult.fail("Quantity must be positive");
    }

    if (order.getLimitPrice() == null || order.getLimitPrice().compareTo(BigDecimal.ZERO) <= 0) {
      return ValidationResult.fail("FOK order requires a positive limit price");
    }

    if (marketState == null || marketState.bidPrice() == null || marketState.askPrice() == null) {
      return ValidationResult.fail("No market data available for " + order.getSymbol());
    }

    // Pre-flight marketability — FOK kills on insufficient liquidity. The simulator uses an
    // "infinite top-of-book" approximation so we only reject non-marketable limits here;
    // depth-aware liquidity checks are a future enhancement.
    if (order.getSide() == Side.BUY
        && order.getLimitPrice().compareTo(marketState.askPrice()) < 0) {
      return ValidationResult.fail(
          "FOK BUY limit is not marketable against current ask " + marketState.askPrice());
    }

    if (order.getSide() == Side.SELL
        && order.getLimitPrice().compareTo(marketState.bidPrice()) > 0) {
      return ValidationResult.fail(
          "FOK SELL limit is not marketable against current bid " + marketState.bidPrice());
    }

    return ValidationResult.ok();
  }

  @Override
  public ExecutionInstruction toExecutionInstruction(Order order) {
    return new ExecutionInstruction(order, TimeInForce.FOK, order.getLimitPrice());
  }
}
