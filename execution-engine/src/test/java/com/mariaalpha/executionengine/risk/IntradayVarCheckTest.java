package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.config.SymbolReferenceConfig;
import com.mariaalpha.executionengine.config.SymbolReferenceConfig.SymbolRef;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.PositionTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntradayVarCheckTest {

  private static final Instant NOW = Instant.parse("2026-07-29T15:00:00Z");

  private MarketStateTracker tracker;
  private PositionTracker positions;
  private PortfolioRiskModel riskModel;
  private SimpleMeterRegistry registry;
  private IntradayVarCheck check;

  @BeforeEach
  void setUp() {
    tracker = mock(MarketStateTracker.class);
    positions = mock(PositionTracker.class);
    riskModel = new PortfolioRiskModel();
    registry = new SimpleMeterRegistry();
    check = build(sumOfAbsolutesConfig(750_000L));
  }

  // ------------------------------------------------------------ pre-4.6.1 behaviour
  // These nine cases are unchanged from before the covariance upgrade. They run against
  // SUM_OF_ABSOLUTES so the original semantics stay pinned.

  @Test
  void zscoreMatchesStandardConfidenceLevels() {
    assertThat(IntradayVarCheck.zscore(0.95)).isCloseTo(1.6449, within(0.001));
    assertThat(IntradayVarCheck.zscore(0.99)).isCloseTo(2.3264, within(0.005));
    assertThat(IntradayVarCheck.zscore(0.90)).isCloseTo(1.2816, within(0.005));
  }

  @Test
  void passesWhenProjectedVarBelowLimit() {
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "200"));
    when(positions.snapshot()).thenReturn(Map.of("AAPL", new BigDecimal("200000")));
    assertThat(check.check(order("AAPL", Side.BUY, 100)).passed()).isTrue();
  }

  @Test
  void failsWhenProjectedVarBreachesLimit() {
    when(tracker.getMarketState("TSLA")).thenReturn(market("TSLA", "250"));
    when(positions.snapshot()).thenReturn(Map.of("TSLA", new BigDecimal("1000000")));
    var result = check.check(order("TSLA", Side.BUY, 80_000));
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("VaR");
  }

  @Test
  void sellsThatReduceVarPass() {
    when(tracker.getMarketState("TSLA")).thenReturn(market("TSLA", "250"));
    when(positions.snapshot()).thenReturn(Map.of("TSLA", new BigDecimal("20000000")));
    assertThat(check.check(order("TSLA", Side.SELL, 1000)).passed()).isTrue();
  }

  @Test
  void disabledWhenLimitIsZero() {
    var disabled = build(sumOfAbsolutesConfig(0L));
    when(tracker.getMarketState("TSLA")).thenReturn(market("TSLA", "250"));
    when(positions.snapshot()).thenReturn(Map.of());
    assertThat(disabled.check(order("TSLA", Side.BUY, 1_000_000)).passed()).isTrue();
  }

  @Test
  void unknownSymbolVolatilityContributesZero() {
    when(tracker.getMarketState("ZZZZ")).thenReturn(market("ZZZZ", "100"));
    when(positions.snapshot()).thenReturn(Map.of());
    assertThat(check.check(order("ZZZZ", Side.BUY, 100_000_000)).passed()).isTrue();
  }

  @Test
  void failsWhenMarketDataMissing() {
    when(tracker.getMarketState("AAPL")).thenReturn(null);
    when(positions.snapshot()).thenReturn(Map.of());
    var result = check.check(order("AAPL", Side.BUY, 100));
    assertThat(result.passed()).isFalse();
    assertThat(result.reason()).contains("Market data unavailable");
  }

  @Test
  void portfolioVarAccumulatesAcrossSymbols() {
    when(tracker.getMarketState("AAPL")).thenReturn(market("AAPL", "200"));
    when(positions.snapshot())
        .thenReturn(
            Map.of(
                "NVDA", new BigDecimal("10000000"),
                "TSLA", new BigDecimal("5000000")));
    assertThat(check.check(order("AAPL", Side.BUY, 100)).passed()).isFalse();
  }

  // ------------------------------------------------------------ 4.6.1 covariance path

  @Test
  void covarianceAggregationIsLowerThanSumOfAbsolutesWhenCorrelationIsBelowOne() {
    riskModel.update(twoSymbolModel(0.30));
    var book = Map.of("NVDA", new BigDecimal("1000000"), "MSFT", new BigDecimal("1000000"));
    when(tracker.getMarketState("NVDA")).thenReturn(market("NVDA", "500"));
    when(positions.snapshot()).thenReturn(book);

    double covarianceVar = currentVarUnder(covarianceConfig(750_000L));
    double sumOfAbsolutesVar = currentVarUnder(sumOfAbsolutesConfig(750_000L));

    assertThat(covarianceVar)
        .as("rho=0.30 must give a strictly smaller portfolio VaR than rho=1")
        .isLessThan(sumOfAbsolutesVar);
  }

  /**
   * The motivating case for roadmap 4.6.1: a nearly-perfectly-hedged long/short pair is rejected by
   * the sum-of-absolutes aggregation and accepted by the covariance one, at the same limit.
   *
   * <p>The limit is chosen to sit between the two projections. With sigma_ann of 0.48 (NVDA) and
   * 0.24 (MSFT), rho = 0.95 and a +$100k NVDA order on a (+$1M, -$1M) book, the covariance path
   * projects ~$32.0k while sum-of-absolutes projects ~$79.6k; $50k separates them cleanly.
   */
  @Test
  void hedgedBookPassesUnderCovarianceButFailsUnderSumOfAbsolutes() {
    riskModel.update(twoSymbolModel(0.95));
    long limit = 50_000L;
    var book = Map.of("NVDA", new BigDecimal("1000000"), "MSFT", new BigDecimal("-1000000"));
    when(tracker.getMarketState("NVDA")).thenReturn(market("NVDA", "500"));
    when(positions.snapshot()).thenReturn(book);

    var underSumOfAbsolutes =
        build(sumOfAbsolutesConfig(limit)).check(order("NVDA", Side.BUY, 200));
    var underCovariance = build(covarianceConfig(limit)).check(order("NVDA", Side.BUY, 200));

    assertThat(underSumOfAbsolutes.passed())
        .as("the pre-4.6.1 aggregation rejects a hedged book")
        .isFalse();
    assertThat(underSumOfAbsolutes.reason()).contains("sum_of_absolutes");
    assertThat(underCovariance.passed())
        .as("the covariance aggregation credits the hedge and lets the order through")
        .isTrue();
  }

  @Test
  void sameSignedBookAtPerfectCorrelationMatchesSumOfAbsolutes() {
    riskModel.update(twoSymbolModel(1.0));
    var book = Map.of("NVDA", new BigDecimal("1000000"), "MSFT", new BigDecimal("2000000"));
    when(tracker.getMarketState("NVDA")).thenReturn(market("NVDA", "500"));
    when(positions.snapshot()).thenReturn(book);

    double covarianceVar = currentVarUnder(covarianceConfig(750_000L));
    double ratio = gauge("mariaalpha_execution_var_diversification_ratio", null);
    double sumOfAbsolutesVar = currentVarUnder(sumOfAbsolutesConfig(750_000L));

    assertThat(covarianceVar)
        .as("at rho=1 with same-signed positions the two aggregations must agree exactly")
        .isCloseTo(sumOfAbsolutesVar, within(1e-6));
    assertThat(ratio).isCloseTo(1.0, within(1e-9));
  }

  @Test
  void diversificationRatioGaugeExceedsOneWhenCorrelationIsBelowOne() {
    riskModel.update(twoSymbolModel(0.20));
    when(tracker.getMarketState("NVDA")).thenReturn(market("NVDA", "500"));
    when(positions.snapshot())
        .thenReturn(Map.of("NVDA", new BigDecimal("1000000"), "MSFT", new BigDecimal("1000000")));

    build(covarianceConfig(750_000L)).check(order("NVDA", Side.BUY, 1));

    assertThat(gauge("mariaalpha_execution_var_diversification_ratio", null)).isGreaterThan(1.0);
    assertThat(gauge("mariaalpha_execution_risk_model_stale", null)).isZero();
    assertThat(gauge("mariaalpha_execution_risk_model_symbols", null)).isEqualTo(2.0);
  }

  @Test
  void stalenessGaugesReflectTheModelWithoutWaitingForAnOrder() {
    // The gauges are scraped continuously but orders arrive sporadically. A gauge cached from the
    // last check would report a perfectly fresh model as stale on a quiet stack — and
    // risk_model_stale == 1 is exactly what an operator would alert on.
    riskModel.update(twoSymbolModel(0.5));
    build(covarianceConfig(750_000L));

    assertThat(gauge("mariaalpha_execution_risk_model_stale", null))
        .as("a fresh model must read as not-stale before any order is evaluated")
        .isZero();
    assertThat(gauge("mariaalpha_execution_risk_model_age_seconds", null))
        .isCloseTo(60.0, within(1.0));
    assertThat(gauge("mariaalpha_execution_risk_model_symbols", null)).isEqualTo(2.0);
  }

  @Test
  void stalenessGaugeIsZeroWhenTheCovariancePathIsNotConfigured() {
    build(sumOfAbsolutesConfig(750_000L));
    assertThat(gauge("mariaalpha_execution_risk_model_stale", null))
        .as("SUM_OF_ABSOLUTES does not depend on the model, so it is never 'stale'")
        .isZero();
  }

  // ------------------------------------------------------------ fallback safety

  @Test
  void fallsBackToSumOfAbsolutesWhenNoModelHasArrived() {
    long limit = 30_000L;
    when(tracker.getMarketState("NVDA")).thenReturn(market("NVDA", "500"));
    when(positions.snapshot())
        .thenReturn(Map.of("NVDA", new BigDecimal("1000000"), "MSFT", new BigDecimal("-1000000")));

    var result = build(covarianceConfig(limit)).check(order("NVDA", Side.BUY, 200));

    assertThat(result.passed()).as("no model must not loosen the gate").isFalse();
    assertThat(result.reason()).contains("sum_of_absolutes");
    assertThat(gauge("mariaalpha_execution_risk_model_stale", null)).isEqualTo(1.0);
  }

  @Test
  void fallsBackToSumOfAbsolutesWhenModelIsStale() {
    var stale = twoSymbolModel(0.95, NOW.minus(Duration.ofMinutes(20)));
    riskModel.update(stale);
    long limit = 30_000L;
    when(tracker.getMarketState("NVDA")).thenReturn(market("NVDA", "500"));
    when(positions.snapshot())
        .thenReturn(Map.of("NVDA", new BigDecimal("1000000"), "MSFT", new BigDecimal("-1000000")));

    var result = build(covarianceConfig(limit)).check(order("NVDA", Side.BUY, 200));

    assertThat(result.passed()).as("a 20-minute-old model must not be trusted").isFalse();
    assertThat(result.reason()).contains("sum_of_absolutes");
    assertThat(gauge("mariaalpha_execution_risk_model_stale", null)).isEqualTo(1.0);
    assertThat(gauge("mariaalpha_execution_risk_model_age_seconds", null)).isGreaterThan(900.0);
  }

  @Test
  void unmodelledSymbolUsesReferenceVolatilityAtPerfectCorrelation() {
    // Model covers NVDA/MSFT only; TSLA is held but unmodelled, so it must be added at rho = 1.
    riskModel.update(twoSymbolModel(0.0));
    when(tracker.getMarketState("NVDA")).thenReturn(market("NVDA", "500"));
    when(positions.snapshot())
        .thenReturn(
            Map.of(
                "NVDA", new BigDecimal("1000000"),
                "MSFT", new BigDecimal("1000000"),
                "TSLA", new BigDecimal("5000000")));

    double withTsla = currentVarUnder(covarianceConfig(750_000L));

    when(positions.snapshot())
        .thenReturn(Map.of("NVDA", new BigDecimal("1000000"), "MSFT", new BigDecimal("1000000")));
    double withoutTsla = currentVarUnder(covarianceConfig(750_000L));

    // TSLA sigma_daily = 0.55/sqrt(252) = 0.034645; 5M notional => 173,227 of sigma, times z.
    double expectedDelta = 5_000_000.0 * 0.55 / Math.sqrt(252.0) * IntradayVarCheck.zscore(0.95);
    assertThat(withTsla - withoutTsla)
        .as("an unmodelled symbol contributes its full standalone VaR — no diversification credit")
        .isCloseTo(expectedDelta, within(1.0));
  }

  @Test
  void sellReducingRiskStillPassesUnderCovariance() {
    riskModel.update(twoSymbolModel(0.30));
    when(tracker.getMarketState("NVDA")).thenReturn(market("NVDA", "500"));
    when(positions.snapshot()).thenReturn(Map.of("NVDA", new BigDecimal("40000000")));

    assertThat(build(covarianceConfig(750_000L)).check(order("NVDA", Side.SELL, 1000)).passed())
        .as("a trade that reduces total risk is never gated, whichever aggregation is used")
        .isTrue();
  }

  // ------------------------------------------------------------------- helpers

  private IntradayVarCheck build(RiskLimitsConfig config) {
    // A fresh registry per check: gauges are keyed by name, so two checks sharing one registry
    // would have the first registration win and the second silently read the wrong instrument.
    registry = new SimpleMeterRegistry();
    return new IntradayVarCheck(
        config,
        tracker,
        positions,
        loadRefData(),
        riskModel,
        registry,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /**
   * Runs a no-op probe order under {@code config} and returns the resulting current-VaR gauge.
   *
   * <p>Reads the gauge through the registry that {@link #build} just installed — comparing two
   * aggregations means building two checks, and each one must be read from its own registry.
   */
  private double currentVarUnder(RiskLimitsConfig config) {
    build(config).check(order("NVDA", Side.BUY, 1));
    return gauge("mariaalpha_execution_var_usd", "current");
  }

  private double gauge(String name, String kindTag) {
    var search = registry.find(name);
    if (kindTag != null) {
      search = search.tag("kind", kindTag);
    }
    var found = search.gauge();
    assertThat(found).as("gauge %s must be registered", name).isNotNull();
    return found.value();
  }

  private static RiskLimitsConfig sumOfAbsolutesConfig(long varLimit) {
    return new RiskLimitsConfig(
        100_000,
        500_000,
        2_000_000,
        50,
        25_000,
        Map.of(),
        0L,
        0L,
        0.0,
        varLimit,
        0.95,
        252.0,
        VarAggregation.SUM_OF_ABSOLUTES,
        900L,
        List.of());
  }

  private static RiskLimitsConfig covarianceConfig(long varLimit) {
    return new RiskLimitsConfig(
        100_000,
        500_000,
        2_000_000,
        50,
        25_000,
        Map.of(),
        0L,
        0L,
        0.0,
        varLimit,
        0.95,
        252.0,
        VarAggregation.COVARIANCE,
        900L,
        List.of());
  }

  /** NVDA/MSFT model whose annualised vols match the reference data, so the two paths compare. */
  private static RiskModelSnapshot twoSymbolModel(double correlation) {
    return twoSymbolModel(correlation, NOW.minus(Duration.ofMinutes(1)));
  }

  private static RiskModelSnapshot twoSymbolModel(double correlation, Instant generatedAt) {
    return new RiskModelSnapshot(
        "cov-test",
        generatedAt,
        "ewma+ledoit_wolf",
        "sample+prior",
        240,
        252.0,
        List.of("NVDA", "MSFT"),
        new double[] {0.48, 0.24},
        new double[][] {{1.0, correlation}, {correlation, 1.0}});
  }

  private static SymbolReferenceData loadRefData() {
    var cfg =
        new SymbolReferenceConfig(
            List.of(
                new SymbolRef("AAPL", "TECH", 1.20, 60_000_000L, 0.28),
                new SymbolRef("MSFT", "TECH", 0.95, 25_000_000L, 0.24),
                new SymbolRef("NVDA", "TECH", 1.65, 250_000_000L, 0.48),
                new SymbolRef("TSLA", "AUTOMOTIVE", 1.80, 90_000_000L, 0.55)),
            new SymbolRef("*", "UNKNOWN", 1.0, 0L, 0.0));
    var data = new SymbolReferenceData(cfg);
    data.load();
    return data;
  }

  private static MarketState market(String symbol, String price) {
    return new MarketState(
        symbol, new BigDecimal(price), new BigDecimal(price), new BigDecimal(price), Instant.now());
  }

  private static Order order(String symbol, Side side, int qty) {
    return new Order(
        new OrderSignal(symbol, side, qty, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
