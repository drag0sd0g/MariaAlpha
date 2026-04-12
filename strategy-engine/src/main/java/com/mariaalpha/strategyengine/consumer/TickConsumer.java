package com.mariaalpha.strategyengine.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.service.StrategyEvaluationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TickConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(TickConsumer.class);

  private final ObjectMapper objectMapper;
  private final StrategyEvaluationService strategyEvaluationService;

  public TickConsumer(
      ObjectMapper objectMapper, StrategyEvaluationService strategyEvaluationService) {
    this.objectMapper = objectMapper;
    this.strategyEvaluationService = strategyEvaluationService;
  }

  @KafkaListener(topics = "${strategy-engine.kafka.ticks-topic}")
  public void onTick(ConsumerRecord<String, String> record) {
    try {
      var tick = objectMapper.readValue(record.value(), MarketTick.class);
      strategyEvaluationService.evaluate(tick);
    } catch (Exception e) {
      LOG.error(
          "Failed to process tick from partition {}, offset {}: {}",
          record.partition(),
          record.offset(),
          e.getMessage(),
          e);
    }
  }
}
