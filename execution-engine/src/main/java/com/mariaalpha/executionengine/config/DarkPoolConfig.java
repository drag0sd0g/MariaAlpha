package com.mariaalpha.executionengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution-engine.dark-pool")
public record DarkPoolConfig(
    String venue,
    long tickIntervalMs,
    double matchProbabilityPerTick,
    int minSpreadBps,
    long fillLatencyMs,
    double partialFillRatio,
    int maxPending,
    long seed) {}
