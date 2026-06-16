package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.PositionTracker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(9)
public class IntradayVarCheck implements RiskCheck {

  private final RiskLimitsConfig config;
  private final MarketStateTracker marketStateTracker;
  private final PositionTracker positionTracker;
  private final SymbolReferenceData refData;

  public IntradayVarCheck(
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
    return "IntradayVar";
  }

  @Override
  public RiskCheckResult check(Order order) {
    long limit = config.maxIntradayVar();
    if (limit <= 0) {
      return RiskCheckResult.pass(name());
    }

    var market = marketStateTracker.getMarketState(order.getSymbol());
    if (market == null || market.lastTradePrice() == null) {
      return RiskCheckResult.fail(
          name(), "Market data unavailable for symbol: " + order.getSymbol());
    }

    double zscore = zscore(config.varConfidenceLevel());
    double sqrtT = Math.sqrt(Math.max(config.varTradingDaysPerYear(), 1.0));

    var current = portfolioVar(positionTracker.snapshot(), zscore, sqrtT);
    var orderNotional = market.lastTradePrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    var projected =
        projectedVar(
            positionTracker.snapshot(),
            order.getSymbol(),
            order.getSide(),
            orderNotional,
            zscore,
            sqrtT);

    if (projected.compareTo(BigDecimal.valueOf(limit)) > 0 && projected.compareTo(current) > 0) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "Projected intraday VaR $%s exceeds limit of $%d at %.0f%% confidence",
              projected.setScale(2, RoundingMode.HALF_UP).toPlainString(),
              limit,
              config.varConfidenceLevel() * 100));
    }
    return RiskCheckResult.pass(name());
  }

  private BigDecimal portfolioVar(Map<String, BigDecimal> positions, double zscore, double sqrtT) {
    return positions.entrySet().stream()
        .map(e -> positionVar(e.getKey(), e.getValue(), zscore, sqrtT))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal projectedVar(
      Map<String, BigDecimal> positions,
      String orderSymbol,
      Side side,
      BigDecimal orderNotional,
      double zscore,
      double sqrtT) {
    var existing = positions.getOrDefault(orderSymbol, BigDecimal.ZERO);
    var delta = side == Side.BUY ? orderNotional : orderNotional.negate();
    var newPosition = existing.add(delta);

    var projected = BigDecimal.ZERO;
    for (var entry : positions.entrySet()) {
      if (entry.getKey().equals(orderSymbol)) {
        continue;
      }
      projected = projected.add(positionVar(entry.getKey(), entry.getValue(), zscore, sqrtT));
    }
    projected = projected.add(positionVar(orderSymbol, newPosition, zscore, sqrtT));
    return projected;
  }

  private BigDecimal positionVar(
      String symbol, BigDecimal positionNotional, double zscore, double sqrtT) {
    double sigmaAnn = refData.annualizedVolatilityOf(symbol);
    if (sigmaAnn <= 0 || positionNotional.signum() == 0) {
      return BigDecimal.ZERO;
    }
    double scalar = sigmaAnn / sqrtT * zscore;
    return positionNotional.abs().multiply(BigDecimal.valueOf(scalar));
  }

  static double zscore(double confidenceLevel) {
    double p = 1.0 - confidenceLevel;
    if (p <= 0 || p >= 1) {
      return 1.6448536;
    }
    double t = Math.sqrt(-2.0 * Math.log(p));
    double c0 = 2.515517;
    double c1 = 0.802853;
    double c2 = 0.010328;
    double d1 = 1.432788;
    double d2 = 0.189269;
    double d3 = 0.001308;
    double numerator = c0 + c1 * t + c2 * t * t;
    double denominator = 1.0 + d1 * t + d2 * t * t + d3 * t * t * t;
    return t - numerator / denominator;
  }
}
