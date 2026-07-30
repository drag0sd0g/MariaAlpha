package com.mariaalpha.executionengine.config;

import com.mariaalpha.executionengine.risk.VarAggregation;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "execution-engine.risk")
public record RiskLimitsConfig(
    long maxOrderNotional,
    long maxPositionPerSymbol,
    long maxPortfolioExposure,
    int maxOpenOrders,
    long maxDailyLoss,
    Map<String, Long> sectorExposureLimits,
    long defaultSectorExposureLimit,
    long maxAbsoluteBetaWeightedExposure,
    double maxAdvParticipation,
    long maxIntradayVar,
    double varConfidenceLevel,
    double varTradingDaysPerYear,
    VarAggregation varAggregation,
    long riskModelMaxAgeSeconds,
    List<CorrelatedCluster> correlatedClusters) {

  /** Default staleness ceiling for the covariance risk model: 15 minutes. */
  public static final long DEFAULT_RISK_MODEL_MAX_AGE_SECONDS = 900L;

  @ConstructorBinding
  public RiskLimitsConfig {
    // Binding an absent `var-aggregation` yields null; the safe default is the conservative
    // pre-4.6.1 behaviour, never the one that can only ever report a smaller number.
    if (varAggregation == null) {
      varAggregation = VarAggregation.SUM_OF_ABSOLUTES;
    }
    if (riskModelMaxAgeSeconds <= 0) {
      riskModelMaxAgeSeconds = DEFAULT_RISK_MODEL_MAX_AGE_SECONDS;
    }
  }

  /**
   * Pre-4.6.1 signature, kept so the nine risk-check test classes that construct this record with
   * thirteen arguments stay untouched. Defaults to {@link VarAggregation#SUM_OF_ABSOLUTES} — the
   * behaviour those tests were written against.
   */
  public RiskLimitsConfig(
      long maxOrderNotional,
      long maxPositionPerSymbol,
      long maxPortfolioExposure,
      int maxOpenOrders,
      long maxDailyLoss,
      Map<String, Long> sectorExposureLimits,
      long defaultSectorExposureLimit,
      long maxAbsoluteBetaWeightedExposure,
      double maxAdvParticipation,
      long maxIntradayVar,
      double varConfidenceLevel,
      double varTradingDaysPerYear,
      List<CorrelatedCluster> correlatedClusters) {
    this(
        maxOrderNotional,
        maxPositionPerSymbol,
        maxPortfolioExposure,
        maxOpenOrders,
        maxDailyLoss,
        sectorExposureLimits,
        defaultSectorExposureLimit,
        maxAbsoluteBetaWeightedExposure,
        maxAdvParticipation,
        maxIntradayVar,
        varConfidenceLevel,
        varTradingDaysPerYear,
        VarAggregation.SUM_OF_ABSOLUTES,
        DEFAULT_RISK_MODEL_MAX_AGE_SECONDS,
        correlatedClusters);
  }

  public RiskLimitsConfig(
      long maxOrderNotional,
      long maxPositionPerSymbol,
      long maxPortfolioExposure,
      int maxOpenOrders,
      long maxDailyLoss,
      Map<String, Long> sectorExposureLimits,
      long defaultSectorExposureLimit,
      long maxAbsoluteBetaWeightedExposure,
      double maxAdvParticipation) {
    this(
        maxOrderNotional,
        maxPositionPerSymbol,
        maxPortfolioExposure,
        maxOpenOrders,
        maxDailyLoss,
        sectorExposureLimits,
        defaultSectorExposureLimit,
        maxAbsoluteBetaWeightedExposure,
        maxAdvParticipation,
        0L,
        0.95,
        252.0,
        List.of());
  }

  public record CorrelatedCluster(String name, List<String> symbols, long limit) {}
}
