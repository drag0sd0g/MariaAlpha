package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.PositionTracker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(7)
public class BetaExposureCheck implements RiskCheck {

  private final RiskLimitsConfig config;
  private final MarketStateTracker marketStateTracker;
  private final PositionTracker positionTracker;
  private final SymbolReferenceData refData;

  public BetaExposureCheck(
      RiskLimitsConfig config,
      MarketStateTracker marketStateTracker,
      PositionTracker positionTracker,
      SymbolReferenceData refData) {
    this.config = config;
    this.marketStateTracker = marketStateTracker;
    this.positionTracker = positionTracker;
    this.refData = refData;
  }

  @Override
  public String name() {
    return "BetaExposure";
  }

  @Override
  public RiskCheckResult check(Order order) {
    long limit = config.maxAbsoluteBetaWeightedExposure();
    if (limit <= 0) {
      return RiskCheckResult.pass(name());
    }

    var market = marketStateTracker.getMarketState(order.getSymbol());
    if (market == null || market.lastTradePrice() == null) {
      return RiskCheckResult.fail(
          name(), "Market data unavailable for symbol: " + order.getSymbol());
    }

    var current = currentBetaWeightedExposure();
    var orderNotional = market.lastTradePrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    var orderBeta = BigDecimal.valueOf(refData.betaOf(order.getSymbol()));
    var orderBetaContribution =
        (order.getSide() == Side.BUY ? orderNotional : orderNotional.negate()).multiply(orderBeta);
    var projected = current.add(orderBetaContribution).abs();
    var currentAbs = current.abs();

    if (projected.compareTo(BigDecimal.valueOf(limit)) > 0 && projected.compareTo(currentAbs) > 0) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "Projected |beta-weighted exposure| $%s exceeds limit of $%d (order β=%s)",
              projected.setScale(2, RoundingMode.HALF_UP).toPlainString(),
              limit,
              orderBeta.toPlainString()));
    }
    return RiskCheckResult.pass(name());
  }

  private BigDecimal currentBetaWeightedExposure() {
    return positionTracker.snapshot().entrySet().stream()
        .map(e -> e.getValue().multiply(BigDecimal.valueOf(refData.betaOf(e.getKey()))))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
