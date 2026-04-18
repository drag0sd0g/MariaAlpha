package com.mariaalpha.executionengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution-engine.risk")
public record RiskLimitsConfig(
    long maxOrderNotional,
    long maxPositionPerSymbol,
    long maxPortfolioExposure,
    int maxOpenOrders,
    long maxDailyLoss) {}
