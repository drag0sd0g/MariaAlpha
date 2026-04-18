package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.PositionTracker;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(2)
public class MaxPositionPerSymbolCheck implements RiskCheck {

  private final RiskLimitsConfig config;
  private final MarketStateTracker marketStateTracker;
  private final PositionTracker positionTracker;

  public MaxPositionPerSymbolCheck(
      RiskLimitsConfig config,
      MarketStateTracker marketStateTracker,
      PositionTracker positionTracker) {
    this.config = config;
    this.marketStateTracker = marketStateTracker;
    this.positionTracker = positionTracker;
  }

  @Override
  public String name() {
    return "MaxPositionPerSymbol";
  }

  @Override
  public RiskCheckResult check(Order order) {
    var market = marketStateTracker.getMarketState(order.getSymbol());
    if (market == null) {
      return RiskCheckResult.fail(
          name(), "Market data not available for symbol: " + order.getSymbol());
    }

    var currentPosition = positionTracker.getPositionNotional(order.getSymbol());
    var orderNotional = market.lastTradePrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    var projectedPosition = currentPosition.add(orderNotional);

    if (projectedPosition.longValue() > config.maxPositionPerSymbol()) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "%s: projected $%s exceeds limit of $%d",
              order.getSymbol(), projectedPosition.toPlainString(), config.maxPositionPerSymbol()));
    }

    return RiskCheckResult.pass(name());
  }
}
