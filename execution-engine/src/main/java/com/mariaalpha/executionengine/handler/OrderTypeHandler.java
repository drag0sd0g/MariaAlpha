package com.mariaalpha.executionengine.handler;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.ValidationResult;

public interface OrderTypeHandler {
  OrderType supportedType();

  ValidationResult validate(Order order, MarketState marketState);

  ExecutionInstruction toExecutionInstruction(Order order);
}
