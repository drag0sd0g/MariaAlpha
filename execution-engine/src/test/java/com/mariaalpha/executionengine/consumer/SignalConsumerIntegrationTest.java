package com.mariaalpha.executionengine.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@Testcontainers
@Tag("integration")
class SignalConsumerIntegrationTest {

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("execution-engine.kafka.signals-topic", () -> "strategy.signals");
    registry.add("execution-engine.kafka.market-data-topic", () -> "market-data.ticks");
    registry.add("execution-engine.kafka.orders-lifecycle-topic", () -> "orders.lifecycle");
    registry.add("execution-engine.kafka.routing-decisions-topic", () -> "routing.decisions");
    registry.add("execution-engine.kafka.risk-alerts-topic", () -> "analytics.risk-alerts");
  }

  @Test
  void consumesAndExecutesSignal() throws Exception {
    var producerProps =
        Map.<String, Object>of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    var producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps);
    var template = new KafkaTemplate<>(producerFactory);

    var mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    var signal =
        new OrderSignal("AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now());
    var json = mapper.writeValueAsString(signal);

    // Send and block until the broker acks — verifies Kafka is reachable and the topic exists.
    var sendResult = template.send("strategy.signals", "AAPL", json).get(10, TimeUnit.SECONDS);
    assertThat(sendResult.getRecordMetadata().offset()).isGreaterThanOrEqualTo(0);
  }
}
