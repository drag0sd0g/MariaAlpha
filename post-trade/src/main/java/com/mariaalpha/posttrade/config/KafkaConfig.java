package com.mariaalpha.posttrade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "post-trade.kafka")
public record KafkaConfig(
    String ordersLifecycleTopic, String marketDataTopic, String analyticsTcaTopic) {}
