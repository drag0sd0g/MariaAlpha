package com.mariaalpha.marketdatagateway.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import com.mariaalpha.marketdatagateway.config.KafkaPublisherConfig;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

@Component
public class TickKafkaPublisher {
  private static final Logger LOG = LoggerFactory.getLogger(TickKafkaPublisher.class);
  private static final String METRIC_NAME = "mariaalpha_md_ticks_received_total";

  private final MarketDataAdapter adapter;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final KafkaPublisherConfig config;
  private final MeterRegistry meterRegistry;
  private volatile Disposable subscription;

  public TickKafkaPublisher(
      MarketDataAdapter adapter,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      KafkaPublisherConfig config,
      MeterRegistry meterRegistry) {
    this.adapter = adapter;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.config = config;
    this.meterRegistry = meterRegistry;
  }

  @PostConstruct
  void start() {
    subscription = adapter.streamTicks().subscribe(this::publishTick);
    LOG.info("Started publishing ticks to Kafka topic: {}", config.ticksTopic());
  }

  @PreDestroy
  void stop() {
    if (subscription != null && !subscription.isDisposed()) {
      subscription.dispose();
    }
    LOG.info("Stopped tick Kafka publisher");
  }

  private void publishTick(MarketTick tick) {
    try {
      var json = objectMapper.writeValueAsString(tick);
      // records are keyed by symbol
      kafkaTemplate.send(config.ticksTopic(), tick.symbol(), json);
      Counter.builder(METRIC_NAME)
          .description("Total market data ticks received")
          .tag("symbol", tick.symbol())
          .tag("event_type", tick.eventType().name())
          .register(meterRegistry)
          .increment();
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialize MarketTick: {}", tick, e);
    }
  }
}
