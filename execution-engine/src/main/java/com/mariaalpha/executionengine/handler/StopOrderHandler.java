package com.mariaalpha.executionengine.handler;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.ValidationResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class StopOrderHandler implements OrderTypeHandler {

  @Override
  public OrderType supportedType() {
    return OrderType.STOP;
  }

  @Override
  public ValidationResult validate(Order order, MarketState marketState) {
    if (order.getQuantity() <= 0) {
      return ValidationResult.fail("Quantity must be positive");
    }
    if (order.getStopPrice() == null || order.getStopPrice().compareTo(BigDecimal.ZERO) <= 0) {
      return ValidationResult.fail("Stop order requires a positive stop price");
    }
    if (marketState == null) {
      return ValidationResult.fail("No market data available for " + order.getSymbol());
    }
    // BUY stop: stopPrice must be above current ask (momentum entry)
    // SELL stop: stopPrice must be below current bid (stop-loss)
    if (order.getSide() == Side.BUY
        && order.getStopPrice().compareTo(marketState.askPrice()) <= 0) {
      return ValidationResult.fail("BUY STOP price must be above current ask");
    }
    if (order.getSide() == Side.SELL
        && order.getStopPrice().compareTo(marketState.bidPrice()) >= 0) {
      return ValidationResult.fail("SELL STOP price must be below current bid");
    }
    return ValidationResult.ok();
  }

  @Override
  public ExecutionInstruction toExecutionInstruction(Order order) {
    return new ExecutionInstruction(order, "day", null);
  }
}
