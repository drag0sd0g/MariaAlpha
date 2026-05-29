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

/**
 * Issue 2.2.2 — pre-trade beta-weighted exposure check.
 *
 * <p>For each open position, beta-weighted exposure is {@code position_notional × beta}. The
 * portfolio's beta-weighted exposure is the signed sum across all positions; its absolute value
 * measures how much the portfolio moves for a 1 % move in the benchmark. The check rejects any
 * order that would push {@code |Σ position × beta|} above the configured ceiling.
 *
 * <p>This catches the case where MaxPortfolioExposure passes (gross $ is fine) but the underlying
 * mix has drifted into a high-beta concentration that would amplify a market drawdown — e.g. a
 * portfolio dominated by NVDA (β ≈ 1.6) and TSLA (β ≈ 1.8) is much riskier than the same dollar
 * gross spread across MSFT (β ≈ 0.9) and broad-market ETFs.
 *
 * <p>SELLs that reduce a long position naturally pull beta-weighted exposure toward zero — the
 * check only fires when the absolute value of the projection grows beyond {@code current}.
 *
 * <p>Self-disables when {@code maxAbsoluteBetaWeightedExposure ≤ 0} so deployments that haven't
 * configured the limit yet still wire correctly.
 */
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
