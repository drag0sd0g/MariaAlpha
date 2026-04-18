package com.mariaalpha.executionengine.router;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RoutingDecision;

public interface SmartOrderRouter {
  RoutingDecision route(Order order);
}
