package com.mariaalpha.ordermanager.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.ordermanager.config.KafkaConfig;
import com.mariaalpha.ordermanager.controller.dto.PositionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PositionUpdatePublisher {

  private static final Logger LOG = LoggerFactory.getLogger(PositionUpdatePublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final KafkaConfig kafkaConfig;

  public PositionUpdatePublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      KafkaConfig kafkaConfig) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.kafkaConfig = kafkaConfig;
  }

  public void publish(PositionSnapshot snapshot) {
    try {
      var payload = objectMapper.writeValueAsString(snapshot);
      kafkaTemplate.send(kafkaConfig.positionsUpdatesTopic(), snapshot.symbol(), payload);
    } catch (JsonProcessingException e) {
      LOG.error(
          "Failed to serialize position snapshot for {}: {}", snapshot.symbol(), e.getMessage());
    }
  }
}
