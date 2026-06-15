package com.mariaalpha.strategyengine.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.strategyengine.algo.AlgoOrderRegistry;
import com.mariaalpha.strategyengine.algo.AlgoProgressPublisher;
import com.mariaalpha.strategyengine.config.KafkaConfig;
import com.mariaalpha.strategyengine.model.OrderSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class SignalPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(SignalPublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final KafkaConfig config;
  private final AlgoOrderRegistry algoOrderRegistry;
  private final AlgoProgressPublisher algoProgressPublisher;

  public SignalPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      KafkaConfig config,
      AlgoOrderRegistry algoOrderRegistry,
      AlgoProgressPublisher algoProgressPublisher) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.config = config;
    this.algoOrderRegistry = algoOrderRegistry;
    this.algoProgressPublisher = algoProgressPublisher;
  }

  public void publish(OrderSignal signal) {
    try {
      var json = objectMapper.writeValueAsString(signal);
      kafkaTemplate.send(config.signalsTopic(), signal.symbol(), json);
      LOG.info(
          "Published signal: {} {} {} shares @ {}",
          signal.side(),
          signal.symbol(),
          signal.quantity(),
          signal.limitPrice());
      algoOrderRegistry
          .activeForSymbol(signal.symbol())
          .forEach(algo -> algoProgressPublisher.publishSignalEmitted(algo, signal));
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialise OrderSignal for {}: {}", signal.symbol(), e.getMessage(), e);
    }
  }
}
