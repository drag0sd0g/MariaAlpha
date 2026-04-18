package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;

public interface RiskCheck {
  String name();

  RiskCheckResult check(Order order);
}
