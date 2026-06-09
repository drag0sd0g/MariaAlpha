package com.mariaalpha.strategyengine.algo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.strategyengine.config.KafkaConfig;
import com.mariaalpha.strategyengine.model.OrderSignal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Producer for the {@code algo.progress} Kafka topic (roadmap 3.4.5). Strategy lifecycle and
 * signal-level events flow through here on their way to the API gateway's WebSocket fan-out.
 *
 * <p>Failures to publish are logged at WARN — the algo order itself is the source of truth and the
 * REST GET surface always returns the current state, so a dropped progress event degrades the UI
 * smoothness without affecting correctness.
 */
@Component
public class AlgoProgressPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(AlgoProgressPublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final KafkaConfig config;

  public AlgoProgressPublisher(
      KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, KafkaConfig config) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.config = config;
  }

  public void publishLifecycle(AlgoOrder order, AlgoProgressEvent.EventType eventType) {
    var event =
        new AlgoProgressEvent(
            order.algoOrderId(),
            eventType,
            order.symbol(),
            order.side(),
            order.targetQuantity(),
            order.strategyName(),
            order.status(),
            null,
            null,
            null,
            Instant.now());
    send(event);
  }

  public void publishSignalEmitted(AlgoOrder order, OrderSignal signal) {
    var event =
        new AlgoProgressEvent(
            order.algoOrderId(),
            AlgoProgressEvent.EventType.SIGNAL_EMITTED,
            order.symbol(),
            order.side(),
            order.targetQuantity(),
            order.strategyName(),
            order.status(),
            signal.side(),
            (long) signal.quantity(),
            signal.limitPrice(),
            Instant.now());
    send(event);
  }

  private void send(AlgoProgressEvent event) {
    try {
      var json = objectMapper.writeValueAsString(event);
      kafkaTemplate.send(config.algoProgressTopic(), event.algoOrderId().toString(), json);
    } catch (JsonProcessingException e) {
      LOG.warn(
          "Failed to serialise algo-progress event for {}: {}",
          event.algoOrderId(),
          e.getMessage());
    }
  }
}
