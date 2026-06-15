package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(8)
public class AdvParticipationCheck implements RiskCheck {

  private final RiskLimitsConfig config;
  private final SymbolReferenceData refData;

  public AdvParticipationCheck(RiskLimitsConfig config, SymbolReferenceData refData) {
    this.config = config;
    this.refData = refData;
  }

  @Override
  public String name() {
    return "AdvParticipation";
  }

  @Override
  public RiskCheckResult check(Order order) {
    double maxParticipation = config.maxAdvParticipation();
    if (maxParticipation <= 0) {
      return RiskCheckResult.pass(name());
    }

    long adv = refData.advOf(order.getSymbol());
    if (adv <= 0) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "ADV unavailable for symbol %s — refusing order as a precaution", order.getSymbol()));
    }

    double participation = (double) order.getQuantity() / (double) adv;
    if (participation > maxParticipation) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "Order %d shares of %s is %.2f%% of ADV %d, exceeds %.2f%% limit",
              order.getQuantity(),
              order.getSymbol(),
              participation * 100.0,
              adv,
              maxParticipation * 100.0));
    }
    return RiskCheckResult.pass(name());
  }
}
