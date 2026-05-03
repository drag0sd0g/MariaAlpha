package com.mariaalpha.ordermanager.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.ordermanager.config.KafkaConfig;
import com.mariaalpha.ordermanager.model.FillEvent;
import com.mariaalpha.ordermanager.model.OrderLifecycleEvent;
import com.mariaalpha.ordermanager.model.OrderSnapshotEvent;
import com.mariaalpha.ordermanager.model.OrderStatus;
import com.mariaalpha.ordermanager.model.OrderType;
import com.mariaalpha.ordermanager.model.Side;
import com.mariaalpha.ordermanager.repository.FillRepository;
import com.mariaalpha.ordermanager.repository.OrderRepository;
import com.mariaalpha.ordermanager.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class OrderLifecycleConsumerIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("mariaalpha")
          .withUsername("mariaalpha")
          .withPassword("mariaalpha");

  @Container
  static KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
  }

  @Autowired private OrderRepository orderRepository;
  @Autowired private FillRepository fillRepository;
  @Autowired private PositionRepository positionRepository;
  @Autowired private KafkaConfig kafkaConfig;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void reset() {
    fillRepository.deleteAll();
    orderRepository.deleteAll();
    positionRepository.deleteAll();
  }

  @Test
  void endToEndLifecyclePersistsOrderFillAndPosition() throws Exception {
    var orderId = UUID.randomUUID();
    publish(lifecycle(orderId, OrderStatus.NEW, 0, null, null));
    publish(lifecycle(orderId, OrderStatus.SUBMITTED, 0, null, null));
    var fill = newFillEvent(orderId, "EX-F-1", BigDecimal.valueOf(150), 10);
    publish(lifecycle(orderId, OrderStatus.PARTIALLY_FILLED, 10, BigDecimal.valueOf(150), fill));

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> {
              var o = orderRepository.findById(orderId).orElseThrow();
              assertThat(o.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
              assertThat(o.getFilledQuantity()).isEqualByComparingTo("10");
              assertThat(fillRepository.findBySymbolOrderByFilledAtDesc("AAPL")).hasSize(1);
              var p = positionRepository.findById("AAPL").orElseThrow();
              assertThat(p.getNetQuantity()).isEqualByComparingTo("10");
              assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("150");
            });
  }

  @Test
  void duplicateFillIsDeduplicated() throws Exception {
    var orderId = UUID.randomUUID();
    publish(lifecycle(orderId, OrderStatus.NEW, 0, null, null));
    publish(lifecycle(orderId, OrderStatus.SUBMITTED, 0, null, null));
    var fill = newFillEvent(orderId, "EX-F-DUP", BigDecimal.valueOf(100), 5);
    publish(lifecycle(orderId, OrderStatus.PARTIALLY_FILLED, 5, BigDecimal.valueOf(100), fill));
    publish(lifecycle(orderId, OrderStatus.PARTIALLY_FILLED, 5, BigDecimal.valueOf(100), fill));

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> {
              var fills = fillRepository.findBySymbolOrderByFilledAtDesc("AAPL");
              assertThat(fills).hasSize(1);
              assertThat(fills.get(0).getExchangeFillId()).isEqualTo("EX-F-DUP");
            });
  }

  @Test
  void positionUpdatePublishedToKafkaTopic() throws Exception {
    var consumerProps = new Properties();
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
      consumer.subscribe(java.util.List.of(kafkaConfig.positionsUpdatesTopic()));

      var orderId = UUID.randomUUID();
      publish(lifecycle(orderId, OrderStatus.NEW, 0, null, null));
      publish(lifecycle(orderId, OrderStatus.SUBMITTED, 0, null, null));
      var fill = newFillEvent(orderId, "EX-F-PUB", BigDecimal.valueOf(175), 20);
      publish(lifecycle(orderId, OrderStatus.FILLED, 20, BigDecimal.valueOf(175), fill));

      Map<String, String> received = new HashMap<>();
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(r -> received.put(r.key(), r.value()));
                return received.containsKey("AAPL");
              });

      assertThat(received.get("AAPL")).contains("\"symbol\":\"AAPL\"");
    }
  }

  private void publish(OrderLifecycleEvent event) throws Exception {
    var props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      producer
          .send(
              new ProducerRecord<>(
                  kafkaConfig.ordersLifecycleTopic(),
                  event.orderId(),
                  objectMapper.writeValueAsString(event)))
          .get();
    }
  }

  private OrderLifecycleEvent lifecycle(
      UUID orderId, OrderStatus status, int filledQty, BigDecimal avgFill, FillEvent fill) {
    var snap =
        new OrderSnapshotEvent(
            orderId.toString(),
            "c-" + orderId,
            "AAPL",
            Side.BUY,
            100,
            OrderType.LIMIT,
            BigDecimal.valueOf(150),
            null,
            "VWAP",
            filledQty,
            avgFill,
            "EX-" + orderId,
            "SIMULATED");
    return new OrderLifecycleEvent(orderId.toString(), status, snap, fill, null, Instant.now());
  }

  private FillEvent newFillEvent(UUID orderId, String exchangeFillId, BigDecimal price, int qty) {
    return new FillEvent(
        UUID.randomUUID().toString(),
        orderId.toString(),
        exchangeFillId,
        "AAPL",
        Side.BUY,
        price,
        qty,
        BigDecimal.ZERO,
        "SIMULATED",
        Instant.now());
  }
}
