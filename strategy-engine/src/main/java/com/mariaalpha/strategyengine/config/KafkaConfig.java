package com.mariaalpha.strategyengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strategy-engine.kafka")
public record KafkaConfig(String ticksTopic, String signalsTopic) {}
