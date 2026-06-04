package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import org.springframework.stereotype.Component;

/**
 * Pre-trade ADV-relative sizing check.
 *
 * <p>Rejects orders whose share quantity exceeds {@code maxAdvParticipation × ADV(symbol)}.
 * Quantitatively, this caps the order's expected market impact: a parent that consumes more than a
 * handful of percent of typical daily volume on a single symbol is essentially guaranteed to push
 * price against itself when worked through any reasonable execution strategy. The check runs
 * against the <b>parent order</b>'s quantity, not its first slice — the intent is to reject parents
 * that are too large to source liquidly regardless of how they're chopped.
 *
 * <p>Self-disables when {@code maxAdvParticipation ≤ 0} so deployments that haven't dialed in a
 * threshold yet still wire the chain. When ADV is missing for a symbol (the conservative default of
 * 0), the check rejects every order on that symbol — the operator must explicitly add reference
 * data before trading.
 */
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
