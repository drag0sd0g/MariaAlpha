package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(1)
public class MaxOrderNotionalCheck implements RiskCheck {

  private final RiskLimitsConfig config;
  private final MarketStateTracker marketStateTracker;

  public MaxOrderNotionalCheck(RiskLimitsConfig config, MarketStateTracker marketStateTracker) {
    this.config = config;
    this.marketStateTracker = marketStateTracker;
  }

  @Override
  public String name() {
    return "MaxOrderNotional";
  }

  @Override
  public RiskCheckResult check(Order order) {
    var market = marketStateTracker.getMarketState(order.getSymbol());
    if (market == null) {
      return RiskCheckResult.fail(
          name(), "Market data unavailable for symbol: " + order.getSymbol());
    }
    var notional = market.lastTradePrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    if (notional.longValue() > config.maxOrderNotional()) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "$%s exceeds limit of $%d", notional.toPlainString(), config.maxOrderNotional()));
    }
    return RiskCheckResult.pass(name());
  }
}
