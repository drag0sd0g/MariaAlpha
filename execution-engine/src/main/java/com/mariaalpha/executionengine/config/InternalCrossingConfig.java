package com.mariaalpha.executionengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution-engine.internal-crossing")
public record InternalCrossingConfig(
    String venue,
    double crossProbabilityOnSubmit,
    long tickIntervalMs,
    double matchProbabilityPerTick,
    long fillLatencyMs,
    int maxPending,
    long seed) {}
