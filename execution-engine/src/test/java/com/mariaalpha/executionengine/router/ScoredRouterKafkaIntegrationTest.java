package com.mariaalpha.executionengine.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.executionengine.adapter.VenueAdapter;
import com.mariaalpha.executionengine.adapter.VenueAdapterRegistry;
import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.metrics.ExecutionMetrics;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.publisher.RoutingDecisionPublisher;
import com.mariaalpha.executionengine.router.scorer.FeeScorer;
import com.mariaalpha.executionengine.router.scorer.InformationLeakageScorer;
import com.mariaalpha.executionengine.router.scorer.LatencyScorer;
import com.mariaalpha.executionengine.router.scorer.LiquidityScorer;
import com.mariaalpha.executionengine.router.scorer.PriceImprovementScorer;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@Tag("integration")
class ScoredRouterKafkaIntegrationTest {

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

  private static KafkaTemplate<String, String> producerTemplate;
  private static KafkaConsumer<String, String> consumer;
  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  @BeforeAll
  static void setUpKafka() {
    var producerProps =
        new HashMap<String, Object>(
            Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(),
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer", "org.apache.kafka.common.serialization.StringSerializer"));
    producerTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

    var consumerProps = new Properties();
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "sor-test");
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000);
    consumer = new KafkaConsumer<>(consumerProps);
    consumer.subscribe(java.util.List.of("routing.decisions"));
  }

  @AfterAll
  static void tearDown() {
    if (consumer != null) {
      consumer.close();
    }
  }

  @Test
  void routingDecisionPublishedWithFullBreakdown() throws Exception {
    var weights = new SorConfig.Weights(0.25, 0.25, 0.10, 0.15, 0.25);
    var venues =
        List.of(
            new Venue("PRIMARY", VenueType.LIT, 50, 3, 2, 1.0, 10000, 0.95, "primary", true),
            new Venue("DARK_POOL_A", VenueType.DARK, 30, 1, 0, 0.2, 0, 0.4, "dark", true),
            new Venue(
                "INTERNAL_CROSS", VenueType.INTERNAL, 1, 0, 0, 0.0, 0, 0.6, "internal", true));
    var config = new SorConfig("scored", 200, 10, 5, 1000, weights, venues);
    var registry = new VenueRegistry(config);
    registry.validate();
    var scorer =
        new VenueScorer(
            List.of(
                new PriceImprovementScorer(),
                new LiquidityScorer(),
                new LatencyScorer(),
                new FeeScorer(),
                new InformationLeakageScorer()),
            config);
    var kafkaConfig =
        new com.mariaalpha.executionengine.config.KafkaConfig(
            "strategy.signals",
            "market-data.ticks",
            "orders.lifecycle",
            "routing.decisions",
            "analytics.risk-alerts");
    var publisher = new RoutingDecisionPublisher(producerTemplate, MAPPER, kafkaConfig);
    var cache = new RoutingDecisionCache(config);
    var tracker = new MarketStateTracker();
    tracker.update(
        new MarketState(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            new BigDecimal("178.52"),
            Instant.now()));
    var metrics = new ExecutionMetrics(new SimpleMeterRegistry());

    var venueAdapters =
        new VenueAdapterRegistry(
            List.of(
                stubAdapter("PRIMARY", VenueType.LIT),
                stubAdapter("DARK_POOL_A", VenueType.DARK),
                stubAdapter("INTERNAL_CROSS", VenueType.INTERNAL)));
    var router =
        new ScoredSmartOrderRouter(
            registry, scorer, publisher, cache, tracker, metrics, config, venueAdapters);

    var order =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "T", Instant.now()));
    router.route(order);

    var record = poll();
    var node = MAPPER.readTree(record.value());

    assertThat(node.get("orderId").asText()).isEqualTo(order.getOrderId());
    assertThat(node.has("selectedVenue")).isFalse();
    assertThat(node.get("venue").asText()).isIn("PRIMARY", "DARK_POOL_A", "INTERNAL_CROSS");
    assertThat(node.get("candidateScores").size()).isEqualTo(3);
    assertThat(node.get("weights").size()).isEqualTo(5);
    assertThat(node.get("marketSnapshot").get("bid").decimalValue()).isEqualByComparingTo("178.50");
    for (JsonNode cs : node.get("candidateScores")) {
      assertThat(cs.get("criteria").size()).isEqualTo(5);
    }
  }

  private static VenueAdapter stubAdapter(String name, VenueType type) {
    return new VenueAdapter() {
      @Override
      public String venueName() {
        return name;
      }

      @Override
      public VenueType venueType() {
        return type;
      }

      @Override
      public com.mariaalpha.executionengine.model.OrderAck submitOrder(
          com.mariaalpha.executionengine.model.ExecutionInstruction instruction) {
        throw new UnsupportedOperationException();
      }

      @Override
      public com.mariaalpha.executionengine.model.OrderAck cancelOrder(String exchangeOrderId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void onExecutionReport(
          java.util.function.Consumer<com.mariaalpha.executionengine.model.ExecutionReport>
              callback) {}

      @Override
      public void start() {}

      @Override
      public void shutdown() {}

      @Override
      public boolean isHealthy() {
        return true;
      }
    };
  }

  private ConsumerRecord<String, String> poll() {
    var ref = new AtomicReference<ConsumerRecord<String, String>>();
    await()
        .atMost(Duration.ofSeconds(15))
        .until(
            () -> {
              ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(500));
              if (recs.isEmpty()) {
                return false;
              }
              ref.set(recs.iterator().next());
              return true;
            });
    return ref.get();
  }
}
