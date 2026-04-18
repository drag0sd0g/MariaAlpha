package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(4)
public class MaxOpenOrdersCheck implements RiskCheck {

  private final RiskLimitsConfig config;
  private final OrderLifecycleManager lifecycleManager;

  public MaxOpenOrdersCheck(RiskLimitsConfig config, OrderLifecycleManager lifecycleManager) {
    this.config = config;
    this.lifecycleManager = lifecycleManager;
  }

  @Override
  public String name() {
    return "MaxOpenOrders";
  }

  @Override
  public RiskCheckResult check(Order order) {
    int openOrderCount = lifecycleManager.getOpenOrderCount();
    if (openOrderCount >= config.maxOpenOrders()) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "%d open orders reaches limit of %d", openOrderCount, config.maxOpenOrders()));
    }
    return RiskCheckResult.pass(name());
  }
}
