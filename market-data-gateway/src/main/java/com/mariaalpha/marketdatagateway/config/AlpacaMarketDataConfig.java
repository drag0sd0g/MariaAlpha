package com.mariaalpha.marketdatagateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market-data.alpaca")
public record AlpacaMarketDataConfig(
    String websocketUrl, String baseUrl, String apiKeyId, String apiSecretKey) {}
