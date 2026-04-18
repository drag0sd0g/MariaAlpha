package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.PositionTracker;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(3)
public class MaxPortfolioExposureCheck implements RiskCheck {

  private final RiskLimitsConfig config;
  private final MarketStateTracker marketStateTracker;
  private final PositionTracker positionTracker;

  public MaxPortfolioExposureCheck(
      RiskLimitsConfig config,
      MarketStateTracker marketStateTracker,
      PositionTracker positionTracker) {
    this.config = config;
    this.marketStateTracker = marketStateTracker;
    this.positionTracker = positionTracker;
  }

  @Override
  public String name() {
    return "MaxPortfolioExposure";
  }

  @Override
  public RiskCheckResult check(Order order) {
    var market = marketStateTracker.getMarketState(order.getSymbol());
    if (market == null) {
      return RiskCheckResult.fail(
          name(), "Market data not available for symbol: " + order.getSymbol());
    }
    var orderNotional = market.lastTradePrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    var currentExposure = positionTracker.getTotalGrossExposure();
    var projectedExposure = currentExposure.add(orderNotional);
    if (projectedExposure.longValue() > config.maxPortfolioExposure()) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "Projected gross exposure $%s exceeds limit of $%d",
              projectedExposure.toPlainString(), config.maxPortfolioExposure()));
    }
    return RiskCheckResult.pass(name());
  }
}
