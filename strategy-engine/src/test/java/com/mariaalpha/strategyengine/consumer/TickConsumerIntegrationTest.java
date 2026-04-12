package com.mariaalpha.strategyengine.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import com.mariaalpha.strategyengine.strategy.vwap.TimeBin;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class TickConsumerIntegrationTest {

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:latest");

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @Autowired private SymbolStrategyRouter router;
  @Autowired private StrategyRegistry strategyRegistry;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private MlSignalClient mlSignalClient;

  @BeforeEach
  void setUp() {
    when(mlSignalClient.getSignal(anyString())).thenReturn(Optional.empty());
    when(mlSignalClient.getCircuitBreakerState()).thenReturn(CircuitBreaker.State.CLOSED);

    // Configure VWAP: 500 shares, single full-day bin, so any trading-hours tick fires a signal
    var vwapStrategy = strategyRegistry.get("VWAP").orElseThrow();
    var volumeProfile = List.of(new TimeBin(LocalTime.of(9, 30), LocalTime.of(16, 0), 1.0));
    vwapStrategy.updateParameters(
        Map.of(
            "targetQuantity", 500,
            "side", "BUY",
            "startTime", "09:30",
            "endTime", "16:00",
            "volumeProfile", volumeProfile));
    router.setActiveStrategy("AAPL", "VWAP");
  }

  @Test
  void tickPublishedToKafkaTriggersSignalOnStrategySignalsTopic() throws Exception {
    // Build a tick at 10:00 America/New_York — inside the single VWAP bin
    var ts =
        ZonedDateTime.of(
                LocalDate.of(2026, 3, 24), LocalTime.of(10, 0), ZoneId.of("America/New_York"))
            .toInstant();
    var tick =
        new MarketTick(
            "AAPL",
            ts,
            EventType.QUOTE,
            BigDecimal.ZERO,
            0L,
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            100L,
            80L,
            0L,
            DataSource.ALPACA,
            false);

    // Create a raw Kafka consumer pointed at strategy.signals
    var props = new HashMap<String, Object>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + Instant.now().toEpochMilli());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    var captured = new AtomicReference<List<ConsumerRecord<String, String>>>();

    try (var consumer = new KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of("strategy.signals"));

      // Publish the tick to market-data.ticks
      kafkaTemplate.send("market-data.ticks", "AAPL", objectMapper.writeValueAsString(tick)).get();

      // Wait for the signal to appear on strategy.signals
      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .until(
              () -> {
                var records = consumer.poll(Duration.ofMillis(200));
                var list = new ArrayList<ConsumerRecord<String, String>>();
                records.forEach(list::add);
                if (!list.isEmpty()) {
                  captured.set(list);
                  return true;
                }
                return false;
              });
    }

    var signal = objectMapper.readValue(captured.get().get(0).value(), OrderSignal.class);
    assertThat(signal.symbol()).isEqualTo("AAPL");
    assertThat(signal.side()).isEqualTo(Side.BUY);
    assertThat(signal.quantity()).isEqualTo(500);
  }
}
