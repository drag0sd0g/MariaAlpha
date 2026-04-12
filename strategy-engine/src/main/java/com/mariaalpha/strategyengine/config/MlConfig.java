package com.mariaalpha.strategyengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strategy-engine.ml")
public record MlConfig(String host, int port, double confidenceThreshold) {}
