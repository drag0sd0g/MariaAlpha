package com.mariaalpha.executionengine.config;

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
    List<CorrelatedCluster> correlatedClusters) {

  @ConstructorBinding
  public RiskLimitsConfig {}

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
