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

/**
 * Pre-trade intraday Value-at-Risk check (roadmap 3.5.1).
 *
 * <p>Computes a parametric (Gaussian) one-day VaR for the projected portfolio at the configured
 * {@code varConfidenceLevel} and rejects any order whose addition would push the projection past
 * {@code maxIntradayVar}.
 *
 * <p>Per-symbol VaR contribution:
 *
 * <pre>
 *   var_i = |position_notional_i| × σ_ann_i / √trading-days × z(confidence)
 * </pre>
 *
 * <p>Portfolio VaR is the sum of |var_i| across all symbols — the conservative reading that assumes
 * no diversification benefit (perfect tail-correlation). A correlation-aware version is a future
 * concern; for an MVP risk check, the no-diversification assumption is the right side to err on.
 *
 * <p>Symbols whose annualised volatility is missing from {@link SymbolReferenceData} contribute 0
 * to the projection — the check is a safety net, not a substitute for proper reference data. If
 * {@code maxIntradayVar ≤ 0} the check self-disables.
 *
 * <p>SELLs that flatten a long position naturally pull VaR toward zero; the check only fires when
 * the projection both exceeds the limit AND exceeds the current portfolio VaR (so a re-balancing
 * trade that reduces total risk is never gated).
 */
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

  /** Sum-of-absolutes VaR across the existing portfolio (no diversification credit). */
  private BigDecimal portfolioVar(Map<String, BigDecimal> positions, double zscore, double sqrtT) {
    return positions.entrySet().stream()
        .map(e -> positionVar(e.getKey(), e.getValue(), zscore, sqrtT))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Portfolio VaR after applying the incoming {@code order} to its symbol's existing position. */
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

  /**
   * Standard-normal one-tail z-score for a confidence level. Inlined Abramowitz &amp; Stegun
   * 26.2.23 rational approximation — accurate to ~4.5×10⁻⁴, plenty for VaR thresholds. Defaults to
   * the 95% z-score (1.645) when the configured level is out of range.
   */
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
