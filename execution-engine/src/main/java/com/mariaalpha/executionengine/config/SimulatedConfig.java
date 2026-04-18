package com.mariaalpha.executionengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution-engine.simulated")
public record SimulatedConfig(
    long fillLatencyMs, int slippageBps, double partialFillRatio, String venue) {}
