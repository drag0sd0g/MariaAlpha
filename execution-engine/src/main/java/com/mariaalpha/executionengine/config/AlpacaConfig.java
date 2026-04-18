package com.mariaalpha.executionengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution-engine.alpaca")
public record AlpacaConfig(
    String apiKey,
    String apiSecret,
    String baseUrl,
    String dataUrl,
    String websocketUrl,
    String venue) {}
