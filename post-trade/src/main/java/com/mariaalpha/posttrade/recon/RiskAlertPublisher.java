package com.mariaalpha.posttrade.recon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.posttrade.config.KafkaConfig;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class RiskAlertPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(RiskAlertPublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final String topic;

  public RiskAlertPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      KafkaConfig kafkaConfig) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.topic = kafkaConfig.riskAlertsTopic();
  }

  static RiskAlertPublisher forTest(
      KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, String topic) {
    var p =
        new RiskAlertPublisher(
            kafkaTemplate, objectMapper, new KafkaConfig(null, null, null, topic));
    return p;
  }

  public void publishBreak(ReconciliationBreak b) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("alertType", "RECON_BREAK");
    payload.put("breakType", b.breakType().name());
    payload.put("severity", b.severity().name());
    payload.put("reconDate", b.reconDate().toString());
    payload.put("orderId", b.orderId() == null ? null : b.orderId().toString());
    payload.put("symbol", b.symbol());
    payload.put("description", b.description());
    payload.put("internalQty", b.internalQty());
    payload.put("externalQty", b.externalQty());
    payload.put("internalPrice", b.internalPrice());
    payload.put("externalPrice", b.externalPrice());
    payload.put("notional", b.notional());
    payload.put("timestamp", Instant.now().toString());
    try {
      String key = b.symbol() == null ? "RECON" : b.symbol();
      kafkaTemplate.send(topic, key, objectMapper.writeValueAsString(payload));
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialize recon break alert for order {}", b.orderId(), e);
    }
  }
}
