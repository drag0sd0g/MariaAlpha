package com.mariaalpha.ordermanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order-manager.kafka")
public record KafkaConfig(
    String ordersLifecycleTopic, String marketDataTopic, String positionsUpdatesTopic) {}
