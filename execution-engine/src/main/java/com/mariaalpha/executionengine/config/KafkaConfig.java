package com.mariaalpha.executionengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution-engine.kafka")
public record KafkaConfig(
    String signalsTopic,
    String marketDataTopic,
    String ordersLifecycleTopic,
    String routingDecisionsTopic,
    String riskAlertsTopic) {}
