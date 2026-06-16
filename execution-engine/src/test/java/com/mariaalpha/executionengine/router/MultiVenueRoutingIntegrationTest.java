package com.mariaalpha.executionengine.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.adapter.SimulatedDarkPoolAdapter;
import com.mariaalpha.executionengine.adapter.SimulatedExchangeAdapter;
import com.mariaalpha.executionengine.adapter.SimulatedInternalCrossingAdapter;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.OrderExecutionService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@Testcontainers
@ActiveProfiles("simulated")
@Tag("integration")
@DirtiesContext
class MultiVenueRoutingIntegrationTest {

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

  @Autowired private OrderExecutionService service;
  @Autowired private MarketStateTracker tracker;
  @Autowired private SimulatedExchangeAdapter primary;
  @Autowired private SimulatedDarkPoolAdapter dark;
  @Autowired private SimulatedInternalCrossingAdapter internal;
  @Autowired private ObjectMapper mapper;

  private static KafkaConsumer<String, String> consumer;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("execution-engine.dark-pool.seed", () -> 7L);
    registry.add("execution-engine.dark-pool.match-probability-per-tick", () -> 1.0);
    registry.add("execution-engine.internal-crossing.seed", () -> 7L);
    registry.add("execution-engine.internal-crossing.cross-probability-on-submit", () -> 1.0);
    registry.add("execution-engine.redis.enabled", () -> "false");
    registry.add("management.health.redis.enabled", () -> "false");
    registry.add(
        "spring.autoconfigure.exclude",
        () ->
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis"
                + ".RedisRepositoriesAutoConfiguration");
  }

  @BeforeAll
  static void setUpConsumer() {
    var props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "multi-venue-test");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    // Refresh topic metadata quickly so we don't miss messages on the auto-created topic.
    props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000);
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(java.util.List.of("routing.decisions"));
  }

  @AfterAll
  static void tearDown() {
    if (consumer != null) {
      consumer.close();
    }
  }

  @Test
  void burstedOrdersTouchEveryVenueType() {
    tracker.update(
        new MarketState(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            new BigDecimal("178.52"),
            Instant.now()));

    int orderCount = 50;
    for (int i = 0; i < orderCount; i++) {
      service.executeSignal(
          new OrderSignal(
              "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "T-" + i, Instant.now()));
    }

    Set<String> seenVenues = new HashSet<>();
    int[] decisions = {0};
    int[] decisionsWithAllThree = {0};
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () -> {
              var recs = consumer.poll(Duration.ofMillis(500));
              for (var r : recs) {
                var node = mapper.readTree(r.value());
                decisions[0]++;
                seenVenues.add(node.get("venue").asText());
                if (node.has("candidateScores") && node.get("candidateScores").size() == 3) {
                  decisionsWithAllThree[0]++;
                }
              }
              return decisions[0] >= orderCount;
            });

    assertThat(decisions[0])
        .as("expected the SOR to publish a routing decision for every order")
        .isGreaterThanOrEqualTo(orderCount);
    assertThat(seenVenues)
        .as("expected the SOR to publish decisions only to configured venues")
        .isSubsetOf(Set.of("SIMULATED", "DARK_POOL_A", "INTERNAL_CROSS"))
        .hasSizeGreaterThanOrEqualTo(1);
    assertThat(decisionsWithAllThree[0])
        .as("expected the SOR to evaluate all 3 venues for every decision")
        .isGreaterThanOrEqualTo(orderCount);
  }
}
