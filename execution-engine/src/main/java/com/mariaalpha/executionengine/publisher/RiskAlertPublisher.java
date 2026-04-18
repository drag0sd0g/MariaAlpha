package com.mariaalpha.executionengine.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.config.KafkaConfig;
import com.mariaalpha.executionengine.model.RiskAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class RiskAlertPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(RiskAlertPublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final KafkaConfig config;

  public RiskAlertPublisher(
      KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, KafkaConfig config) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.config = config;
  }

  public void publish(RiskAlert riskAlert) {
    try {
      var riskAlertAsJson = objectMapper.writeValueAsString(riskAlert);
      kafkaTemplate.send(config.riskAlertsTopic(), riskAlert.symbol(), riskAlertAsJson);
      LOG.warn(
          "Published risk alert: {} {} — {}",
          riskAlert.severity(),
          riskAlert.alertType(),
          riskAlert.message());
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialise RiskAlert: {}", e.getMessage(), e);
    }
  }
}
