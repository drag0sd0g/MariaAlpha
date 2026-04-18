package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RiskCheckChain {

  private static final Logger LOG = LoggerFactory.getLogger(RiskCheckChain.class);

  private final List<RiskCheck> checks;

  public RiskCheckChain(List<RiskCheck> checks) {
    this.checks = List.copyOf(checks);
    LOG.info(
        "RiskCheckChain initialized with {} checks: {}",
        checks.size(),
        checks.stream().map(RiskCheck::name).toList());
  }

  /** Runs all checks in order. Short-circuits on the first failure. */
  public RiskCheckResult evaluate(Order order) {
    for (var check : checks) {
      var result = check.check(order);
      if (!result.passed()) {
        LOG.info(
            "Risk check {} FAILED for order {}: {}",
            check.name(),
            order.getOrderId(),
            result.reason());
        return result;
      }
    }
    return RiskCheckResult.pass("ALL");
  }
}
