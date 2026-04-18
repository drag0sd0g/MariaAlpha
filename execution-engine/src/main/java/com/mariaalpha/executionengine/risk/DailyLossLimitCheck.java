package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(5)
public class DailyLossLimitCheck implements RiskCheck {

  private final DailyLossMonitor dailyLossMonitor;

  public DailyLossLimitCheck(DailyLossMonitor dailyLossMonitor) {
    this.dailyLossMonitor = dailyLossMonitor;
  }

  @Override
  public String name() {
    return "DailyLossLimit";
  }

  @Override
  public RiskCheckResult check(Order order) {
    if (dailyLossMonitor.isTradingHalted()) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "Trading halted — daily P&L $%s exceeds loss limit",
              dailyLossMonitor.getDailyPnl().toPlainString()));
    }
    return RiskCheckResult.pass(name());
  }
}
