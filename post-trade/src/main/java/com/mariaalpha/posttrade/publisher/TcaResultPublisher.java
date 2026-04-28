package com.mariaalpha.posttrade.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.posttrade.config.KafkaConfig;
import com.mariaalpha.posttrade.controller.dto.TcaResponse;
import com.mariaalpha.posttrade.entity.TcaResultEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TcaResultPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(TcaResultPublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final KafkaConfig kafkaConfig;

  public TcaResultPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      KafkaConfig kafkaConfig) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.kafkaConfig = kafkaConfig;
  }

  public void publish(TcaResultEntity entity) {
    TcaResponse payload = TcaResponse.of(entity);
    try {
      String json = objectMapper.writeValueAsString(payload);
      kafkaTemplate.send(kafkaConfig.analyticsTcaTopic(), entity.getOrderId().toString(), json);
    } catch (JsonProcessingException e) {
      LOG.error(
          "Failed to serialize TCA result for order {}: {}", entity.getOrderId(), e.getMessage());
    }
  }
}
