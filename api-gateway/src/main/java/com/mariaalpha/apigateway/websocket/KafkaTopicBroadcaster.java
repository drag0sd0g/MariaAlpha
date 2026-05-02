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

/**
 * Subscribes to the four MariaAlpha event topics and fans out each record to a per-topic {@link
 * reactor.core.publisher.Sinks.Many}. WebSocket handlers grab the corresponding {@link Flux} and
 * pipe it to their session.
 */
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

  /** Subscribers obtain the {@link Flux} for a given topic. Returns an empty Flux if unknown. */
  public Flux<String> stream(String topic) {
    Sinks.Many<String> sink = sinks.get(topic);
    if (sink == null) {
      LOG.warn("requested stream for unknown topic {}", topic);
      return Flux.empty();
    }
    return sink.asFlux();
  }

  // The four Kafka listeners. We deliberately use distinct annotated methods (rather than a single
  // dynamic registration) so Spring's metrics tag each listener with its method name.

  @KafkaListener(
      topics = "${mariaalpha.gateway.websocket.endpoints.market-data.topic}",
      groupId = "api-gateway-${random.uuid}-market-data",
      autoStartup = "true",
      properties = {"auto.offset.reset=latest"})
  void onMarketData(ConsumerRecord<String, String> record) {
    forward("market-data.ticks", record);
  }

  @KafkaListener(
      topics = "${mariaalpha.gateway.websocket.endpoints.positions.topic}",
      groupId = "api-gateway-${random.uuid}-positions",
      autoStartup = "true",
      properties = {"auto.offset.reset=latest"})
  void onPositions(ConsumerRecord<String, String> record) {
    forward("positions.updates", record);
  }

  @KafkaListener(
      topics = "${mariaalpha.gateway.websocket.endpoints.orders.topic}",
      groupId = "api-gateway-${random.uuid}-orders",
      autoStartup = "true",
      properties = {"auto.offset.reset=latest"})
  void onOrders(ConsumerRecord<String, String> record) {
    forward("orders.lifecycle", record);
  }

  @KafkaListener(
      topics = "${mariaalpha.gateway.websocket.endpoints.alerts.topic}",
      groupId = "api-gateway-${random.uuid}-alerts",
      autoStartup = "true",
      properties = {"auto.offset.reset=latest"})
  void onAlerts(ConsumerRecord<String, String> record) {
    forward("analytics.risk-alerts", record);
  }

  private void forward(String topic, ConsumerRecord<String, String> record) {
    Sinks.Many<String> sink = sinks.get(topic);
    if (sink == null) {
      return;
    }
    var sinkEmitResult = sink.tryEmitNext(record.value());
    if (sinkEmitResult.isFailure()) {
      LOG.debug("emit failed for topic {}: {}", topic, sinkEmitResult);
    }
  }
}
