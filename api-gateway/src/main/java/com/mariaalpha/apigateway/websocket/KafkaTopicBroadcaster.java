package com.mariaalpha.apigateway.websocket;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class KafkaTopicBroadcaster {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicBroadcaster.class);

  private final Map<String, Sinks.Many<String>> sinks = new HashMap<>();
  private final WebSocketProperties properties;
  private final KafkaListenerEndpointRegistry registry;

  public KafkaTopicBroadcaster(
      WebSocketProperties properties, KafkaListenerEndpointRegistry registry) {
    this.properties = properties;
    this.registry = registry;
  }

  @PostConstruct
  void initialize() {
    if (properties.endpoints() == null) {
      return;
    }
    for (var endpoint : properties.endpoints().values()) {
      sinks.put(
          endpoint.topic(),
          Sinks.many()
              .multicast()
              .onBackpressureBuffer(properties.backpressureBufferSize(), false));
    }
    LOG.info("KafkaTopicBroadcaster initialized for topics: {}", sinks.keySet());
  }

  @PreDestroy
  void teardown() {
    sinks.values().forEach(Sinks.Many::tryEmitComplete);
    sinks.clear();
  }

  public Flux<String> stream(String topic) {
    Sinks.Many<String> sink = sinks.get(topic);
    if (sink == null) {
      LOG.warn("requested stream for unknown topic {}", topic);
      return Flux.empty();
    }
    return sink.asFlux();
  }

  @KafkaListener(
      topics = "${mariaalpha.gateway.websocket.endpoints.market-data.topic}",
      groupId = "api-gateway-${random.uuid}-market-data",
      autoStartup = "true",
      properties = {"auto.offset.reset=latest"})
  void onMarketData(ConsumerRecord<String, String> record) {
    forward(record);
  }

  @KafkaListener(
      topics = "${mariaalpha.gateway.websocket.endpoints.positions.topic}",
      groupId = "api-gateway-${random.uuid}-positions",
      autoStartup = "true",
      properties = {"auto.offset.reset=latest"})
  void onPositions(ConsumerRecord<String, String> record) {
    forward(record);
  }

  @KafkaListener(
      topics = "${mariaalpha.gateway.websocket.endpoints.orders.topic}",
      groupId = "api-gateway-${random.uuid}-orders",
      autoStartup = "true",
      properties = {"auto.offset.reset=latest"})
  void onOrders(ConsumerRecord<String, String> record) {
    forward(record);
  }

  @KafkaListener(
      topics = "${mariaalpha.gateway.websocket.endpoints.alerts.topic}",
      groupId = "api-gateway-${random.uuid}-alerts",
      autoStartup = "true",
      properties = {"auto.offset.reset=latest"})
  void onAlerts(ConsumerRecord<String, String> record) {
    forward(record);
  }

  @KafkaListener(
      topics = "${mariaalpha.gateway.websocket.endpoints.algo.topic}",
      groupId = "api-gateway-${random.uuid}-algo",
      autoStartup = "true",
      properties = {"auto.offset.reset=latest"})
  void onAlgoProgress(ConsumerRecord<String, String> record) {
    forward(record);
  }

  private void forward(ConsumerRecord<String, String> record) {
    Sinks.Many<String> sink = sinks.get(record.topic());
    if (sink == null) {
      return;
    }
    var sinkEmitResult = sink.tryEmitNext(record.value());
    if (sinkEmitResult.isFailure()) {
      LOG.debug("emit failed for topic {}: {}", record.topic(), sinkEmitResult);
    }
  }
}
