package com.mariaalpha.marketdatagateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market-data.kafka")
public record KafkaPublisherConfig(String ticksTopic) {}
