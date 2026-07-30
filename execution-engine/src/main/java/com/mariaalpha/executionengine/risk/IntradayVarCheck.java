package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.PositionTracker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pre-trade Value-at-Risk gate.
 *
 * <p><strong>Aggregation (roadmap 4.6.1).</strong> Per-position VaR is {@code |v_i| sigma_i z}. How
 * those combine into a portfolio number is the whole question:
 *
 * <pre>
 *   SUM_OF_ABSOLUTES:  VaR = z sum_i |v_i| sigma_i     (rho = 1 everywhere)
 *   COVARIANCE:        VaR = z sqrt(v' Sigma_d v)      (signed v, estimated correlations)
 * </pre>
 *
 * <p>The first is the pre-4.6.1 behaviour. It is a valid upper bound, but it credits no
 * diversification and — because it takes absolute values — scores a perfectly hedged long/short
 * book identically to a doubled-up directional one. For {@code v = (+1M, -1M)} at 2% daily
 * volatility and rho = 0.95 it reports $65,794 where the covariance form reports $10,403: a 6.3x
 * overstatement on a book that is very nearly flat.
 *
 * <p><strong>Safety.</strong> Covariance VaR is always less than or equal to the sum of absolutes,
 * so a stale or absent model would silently <em>loosen</em> this gate. Three guards prevent that:
 *
 * <ul>
 *   <li>no model, or one older than {@code risk-model-max-age-seconds}, falls back to {@code
 *       SUM_OF_ABSOLUTES} and raises the {@code risk_model_stale} gauge;
 *   <li>a symbol held but absent from the model keeps its configured volatility and aggregates at
 *       rho = 1 against everything else — conservative when uninformed;
 *   <li>the "projected exceeds the limit <em>and</em> exceeds current" rule is unchanged, so a
 *       trade that reduces total risk is still never blocked.
 * </ul>
 */
@Component
@org.springframework.core.annotation.Order(9)
public class IntradayVarCheck implements RiskCheck {

  private static final Logger LOG = LoggerFactory.getLogger(IntradayVarCheck.class);
  private static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

  private final RiskLimitsConfig config;
  private final MarketStateTracker marketStateTracker;
  private final PositionTracker positionTracker;
  private final SymbolReferenceData refData;
  private final PortfolioRiskModel riskModel;
  private final Clock clock;
  private final AtomicReference<Instant> lastFallbackWarning = new AtomicReference<>();

  private volatile double currentVarGauge;
  private volatile double projectedVarGauge;
  private volatile double diversificationRatioGauge = 1.0;

  public IntradayVarCheck(
      RiskLimitsConfig config,
      MarketStateTracker marketStateTracker,
      PositionTracker positionTracker,
      SymbolReferenceData refData,
      PortfolioRiskModel riskModel,
      MeterRegistry meterRegistry,
      Clock clock) {
    this.config = config;
    this.marketStateTracker = marketStateTracker;
    this.positionTracker = positionTracker;
    this.refData = refData;
    this.riskModel = riskModel;
    this.clock = clock;
    registerMetrics(meterRegistry);
  }

  /** Spring's constructor — system clock. The seven-arg form exists so tests can inject one. */
  @org.springframework.beans.factory.annotation.Autowired
  public IntradayVarCheck(
      RiskLimitsConfig config,
      MarketStateTracker marketStateTracker,
      PositionTracker positionTracker,
      SymbolReferenceData refData,
      PortfolioRiskModel riskModel,
      MeterRegistry meterRegistry) {
    this(
        config,
        marketStateTracker,
        positionTracker,
        refData,
        riskModel,
        meterRegistry,
        Clock.systemUTC());
  }

  private void registerMetrics(MeterRegistry registry) {
    if (registry == null) {
      return;
    }
    Gauge.builder("mariaalpha_execution_var_usd", this, v -> v.currentVarGauge)
        .description("Portfolio VaR in USD as measured by the pre-trade gate")
        .tag("kind", "current")
        .register(registry);
    Gauge.builder("mariaalpha_execution_var_usd", this, v -> v.projectedVarGauge)
        .description("Projected portfolio VaR in USD including the order under evaluation")
        .tag("kind", "projected")
        .register(registry);
    Gauge.builder(
            "mariaalpha_execution_var_diversification_ratio",
            this,
            v -> v.diversificationRatioGauge)
        .description("Sum-of-absolutes VaR divided by covariance VaR; 1.0 means no credit")
        .register(registry);
    // Staleness and age are evaluated at scrape time, not cached from the last order check. A
    // quiet stack checks no orders, so a cached gauge would report a perfectly fresh model as
    // stale until the next trade — and `risk_model_stale == 1` is exactly the condition an
    // operator would page on.
    Gauge.builder("mariaalpha_execution_risk_model_stale", this, v -> v.modelStale() ? 1.0 : 0.0)
        .description("1 when the covariance risk model is absent or older than the max age")
        .register(registry);
    Gauge.builder(
            "mariaalpha_execution_risk_model_age_seconds",
            this,
            v -> v.riskModel.age(v.clock).toMillis() / 1000.0)
        .description("Age in seconds of the cached covariance risk model")
        .register(registry);
    Gauge.builder("mariaalpha_execution_risk_model_symbols", this, v -> (double) v.riskModel.size())
        .description("Number of symbols in the cached covariance risk model")
        .register(registry);
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
    var positions = positionTracker.snapshot();
    var orderNotional = market.lastTradePrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    var delta = order.getSide() == Side.BUY ? orderNotional : orderNotional.negate();

    boolean useCovariance = useCovariance();
    VarPair var =
        useCovariance
            ? covarianceVar(positions, order.getSymbol(), delta, zscore, sqrtT)
            : sumOfAbsolutesVar(positions, order.getSymbol(), delta, zscore, sqrtT);

    recordGauges(var);

    if (var.projected().compareTo(BigDecimal.valueOf(limit)) > 0
        && var.projected().compareTo(var.current()) > 0) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "Projected intraday VaR $%s exceeds limit of $%d at %.0f%% confidence"
                  + " (%s aggregation, diversification ratio %.2f)",
              var.projected().setScale(2, RoundingMode.HALF_UP).toPlainString(),
              limit,
              config.varConfidenceLevel() * 100,
              useCovariance ? "covariance" : "sum_of_absolutes",
              var.diversificationRatio()));
    }
    return RiskCheckResult.pass(name());
  }

  /**
   * True when the covariance path is both configured and trustworthy.
   *
   * <p>The staleness half matters most: without it, killing analytics-service would quietly widen
   * the risk limit rather than tighten it.
   */
  private boolean useCovariance() {
    if (config.varAggregation() != VarAggregation.COVARIANCE) {
      return false;
    }
    if (!modelStale()) {
      return true;
    }
    warnFallback(maxModelAge());
    return false;
  }

  /** Whether the cached model is absent or past its age ceiling. Cheap enough to call per scrape. */
  private boolean modelStale() {
    if (config.varAggregation() != VarAggregation.COVARIANCE) {
      return false;
    }
    return !riskModel.isUsable(maxModelAge(), clock);
  }

  private Duration maxModelAge() {
    return Duration.ofSeconds(Math.max(config.riskModelMaxAgeSeconds(), 0));
  }

  private void warnFallback(Duration maxAge) {
    var now = clock.instant();
    var previous = lastFallbackWarning.get();
    if (previous != null && Duration.between(previous, now).compareTo(WARN_INTERVAL) < 0) {
      return;
    }
    if (!lastFallbackWarning.compareAndSet(previous, now)) {
      return;
    }
    if (!riskModel.isPresent()) {
      LOG.warn(
          "No covariance risk model received on analytics.risk-model yet — falling back to the"
              + " conservative sum_of_absolutes VaR aggregation");
    } else {
      LOG.warn(
          "Covariance risk model {} is {}s old (max {}s) — falling back to the conservative"
              + " sum_of_absolutes VaR aggregation",
          riskModel.modelId(),
          riskModel.age(clock).toSeconds(),
          maxAge.toSeconds());
    }
  }

  /** Current and projected portfolio VaR, plus the credit the covariance form bought. */
  private record VarPair(BigDecimal current, BigDecimal projected, double diversificationRatio) {}

  private VarPair sumOfAbsolutesVar(
      Map<String, BigDecimal> positions,
      String orderSymbol,
      BigDecimal delta,
      double zscore,
      double sqrtT) {
    var current = BigDecimal.ZERO;
    var projected = BigDecimal.ZERO;
    boolean seen = false;
    for (var entry : positions.entrySet()) {
      var standalone = positionVar(entry.getKey(), entry.getValue(), zscore, sqrtT);
      current = current.add(standalone);
      if (entry.getKey().equals(orderSymbol)) {
        projected =
            projected.add(positionVar(orderSymbol, entry.getValue().add(delta), zscore, sqrtT));
        seen = true;
      } else {
        projected = projected.add(standalone);
      }
    }
    if (!seen) {
      projected = projected.add(positionVar(orderSymbol, delta, zscore, sqrtT));
    }
    return new VarPair(current, projected, 1.0);
  }

  /**
   * Covariance aggregation, with a conservative treatment of unmodelled names.
   *
   * <p>Modelled symbols combine through {@code sqrt(v' Sigma_d v)}. Symbols the model does not
   * cover keep their configured volatility and are summed at rho = 1 — both among themselves and
   * against the modelled block — so an incomplete model never manufactures diversification it has
   * no evidence for.
   */
  private VarPair covarianceVar(
      Map<String, BigDecimal> positions,
      String orderSymbol,
      BigDecimal delta,
      double zscore,
      double sqrtT) {
    int n = riskModel.size();
    double[] modelled = new double[n];
    var unmodelled = new ArrayList<Map.Entry<String, BigDecimal>>();

    for (var entry : positions.entrySet()) {
      var index = riskModel.indexOf(entry.getKey());
      if (index.isPresent()) {
        modelled[index.getAsInt()] = entry.getValue().doubleValue();
      } else {
        unmodelled.add(entry);
      }
    }

    double modelledSigma = riskModel.portfolioSigma(modelled);
    double[] covTimesV = riskModel.covarianceTimesVector(modelled);

    double unmodelledSigma = 0.0;
    for (var entry : unmodelled) {
      unmodelledSigma += standaloneSigma(entry.getKey(), entry.getValue(), sqrtT);
    }
    double currentTotal = modelledSigma + unmodelledSigma;

    var orderIndex = riskModel.indexOf(orderSymbol);
    double projectedTotal;
    if (orderIndex.isPresent()) {
      // Rank-1 update: only coordinate k moves, so a full O(N^2) recompute is unnecessary.
      double projectedModelled =
          riskModel.sigmaAfterDelta(
              covTimesV, modelledSigma, orderIndex.getAsInt(), delta.doubleValue());
      projectedTotal = projectedModelled + unmodelledSigma;
    } else {
      var existing = positions.getOrDefault(orderSymbol, BigDecimal.ZERO);
      double before = standaloneSigma(orderSymbol, existing, sqrtT);
      double after = standaloneSigma(orderSymbol, existing.add(delta), sqrtT);
      projectedTotal = currentTotal - before + after;
    }

    // What the pre-4.6.1 aggregation would have reported — the ratio makes the upgrade auditable.
    double sumOfAbsolutes = 0.0;
    for (var entry : positions.entrySet()) {
      sumOfAbsolutes += standaloneSigma(entry.getKey(), entry.getValue(), sqrtT);
    }
    double ratio = currentTotal > 0 ? sumOfAbsolutes / currentTotal : 1.0;

    return new VarPair(
        BigDecimal.valueOf(currentTotal * zscore),
        BigDecimal.valueOf(projectedTotal * zscore),
        ratio);
  }

  /** Standalone daily sigma contribution in USD; model volatility first, then reference data. */
  private double standaloneSigma(String symbol, BigDecimal notional, double sqrtT) {
    if (notional == null || notional.signum() == 0) {
      return 0.0;
    }
    double dailyVol = riskModel.dailyVolatility(symbol);
    if (dailyVol <= 0) {
      double annual = refData.annualizedVolatilityOf(symbol);
      if (annual <= 0) {
        return 0.0;
      }
      dailyVol = annual / sqrtT;
    }
    return Math.abs(notional.doubleValue()) * dailyVol;
  }

  private void recordGauges(VarPair var) {
    currentVarGauge = var.current().doubleValue();
    projectedVarGauge = var.projected().doubleValue();
    diversificationRatioGauge = var.diversificationRatio();
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
