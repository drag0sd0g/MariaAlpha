package com.mariaalpha.executionengine.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.config.KafkaConfig;
import com.mariaalpha.executionengine.model.RoutingDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class RoutingDecisionPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(RoutingDecisionPublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final KafkaConfig config;

  public RoutingDecisionPublisher(
      KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, KafkaConfig config) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.config = config;
  }

  public void publish(RoutingDecision decision) {
    try {
      var decisionAsJson = objectMapper.writeValueAsString(decision);
      kafkaTemplate.send(config.ordersLifecycleTopic(), decision.orderId(), decisionAsJson);
      LOG.debug("Published routing decision: {} -> {}", decision.orderId(), decision.venue());
    } catch (JsonProcessingException e) {
      LOG.error(
          "Failed to serialise RoutingDecision for {}: {}", decision.orderId(), e.getMessage(), e);
    }
  }
}
