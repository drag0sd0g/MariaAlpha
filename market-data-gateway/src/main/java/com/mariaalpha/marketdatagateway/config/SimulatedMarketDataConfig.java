package com.mariaalpha.marketdatagateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market-data.simulated")
public record SimulatedMarketDataConfig(String csvPath, double speedMultiplier) {}
