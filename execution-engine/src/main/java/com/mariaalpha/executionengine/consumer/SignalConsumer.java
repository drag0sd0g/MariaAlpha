package com.mariaalpha.executionengine.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.service.OrderExecutionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SignalConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(SignalConsumer.class);

  private final ObjectMapper objectMapper;
  private final OrderExecutionService executionService;

  public SignalConsumer(ObjectMapper objectMapper, OrderExecutionService executionService) {
    this.objectMapper = objectMapper;
    this.executionService = executionService;
  }

  @KafkaListener(topics = "${execution-engine.kafka.signals-topic}")
  public void onSignal(ConsumerRecord<String, String> record) {
    try {
      var signal = objectMapper.readValue(record.value(), OrderSignal.class);
      executionService.executeSignal(signal);
    } catch (Exception e) {
      LOG.error(
          "Failed to process signal from partition {}, offset {}: {}",
          record.partition(),
          record.offset(),
          e.getMessage(),
          e);
    }
  }
}
