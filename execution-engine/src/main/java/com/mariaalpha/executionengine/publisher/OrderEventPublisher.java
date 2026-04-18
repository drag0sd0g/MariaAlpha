package com.mariaalpha.executionengine.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.config.KafkaConfig;
import com.mariaalpha.executionengine.model.OrderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderEventPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(OrderEventPublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final KafkaConfig config;
  private final ObjectMapper objectMapper;

  public OrderEventPublisher(
      KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, KafkaConfig config) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.config = config;
  }

  public void publish(OrderEvent event) {
    try {
      var eventAsJson = objectMapper.writeValueAsString(event);
      kafkaTemplate.send(config.ordersLifecycleTopic(), event.orderId(), eventAsJson);
      LOG.debug("Published order event: {} -> {}", event.orderId(), event.status());
    } catch (JsonProcessingException jex) {
      LOG.error("Failed to serialize OrderEvent for {}: {}", event.orderId(), jex.getMessage());
    }
  }
}
