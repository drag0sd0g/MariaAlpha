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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
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

    var captured = new AtomicReference<OrderSignal>();

    try (var consumer = new KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of("strategy.signals"));

      // Publish the tick to market-data.ticks
      kafkaTemplate.send("market-data.ticks", "AAPL", objectMapper.writeValueAsString(tick)).get();

      // Wait for the AAPL signal to appear on strategy.signals. Filter by symbol: this topic is
      // shared across all the test methods in this class and read from earliest, so other methods'
      // signals (MSFT/GOOGL/AMZN) may already sit ahead of ours regardless of execution order.
      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .until(
              () -> {
                var records = consumer.poll(Duration.ofMillis(200));
                for (var record : records) {
                  var signal = objectMapper.readValue(record.value(), OrderSignal.class);
                  if ("AAPL".equals(signal.symbol())) {
                    captured.set(signal);
                    return true;
                  }
                }
                return false;
              });
    }

    var signal = captured.get();
    assertThat(signal.symbol()).isEqualTo("AAPL");
    assertThat(signal.side()).isEqualTo(Side.BUY);
    assertThat(signal.quantity()).isEqualTo(500);
  }

  @Test
  void twapTickPublishedToKafkaTriggersSignalOnStrategySignalsTopic() throws Exception {
    // Configure TWAP: 300 shares, single full-day slice, so any trading-hours tick fires it.
    var twapStrategy = strategyRegistry.get("TWAP").orElseThrow();
    twapStrategy.updateParameters(
        Map.of(
            "targetQuantity", 300,
            "side", "SELL",
            "startTime", "09:30",
            "endTime", "16:00",
            "numSlices", 1));
    router.setActiveStrategy("MSFT", "TWAP");

    var ts =
        ZonedDateTime.of(
                LocalDate.of(2026, 3, 24), LocalTime.of(11, 0), ZoneId.of("America/New_York"))
            .toInstant();
    var tick =
        new MarketTick(
            "MSFT",
            ts,
            EventType.QUOTE,
            BigDecimal.ZERO,
            0L,
            new BigDecimal("415.30"),
            new BigDecimal("415.40"),
            100L,
            80L,
            0L,
            DataSource.ALPACA,
            false);

    var props = new HashMap<String, Object>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-twap-" + Instant.now().toEpochMilli());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    var captured = new AtomicReference<OrderSignal>();

    try (var consumer = new KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of("strategy.signals"));

      kafkaTemplate.send("market-data.ticks", "MSFT", objectMapper.writeValueAsString(tick)).get();

      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .until(
              () -> {
                var records = consumer.poll(Duration.ofMillis(200));
                for (var record : records) {
                  var signal = objectMapper.readValue(record.value(), OrderSignal.class);
                  if ("MSFT".equals(signal.symbol())) {
                    captured.set(signal);
                    return true;
                  }
                }
                return false;
              });
    }

    var signal = captured.get();
    assertThat(signal.symbol()).isEqualTo("MSFT");
    assertThat(signal.side()).isEqualTo(Side.SELL);
    assertThat(signal.quantity()).isEqualTo(300);
    assertThat(signal.strategyName()).isEqualTo("TWAP");
  }

  @Test
  void implementationShortfallTickPublishedToKafkaTriggersFrontLoadedSignal() throws Exception {
    // Configure IS: 1000 shares over two equal half-day slices with urgency 0.5. A tick inside
    // the first slice fires its front-loaded allocation — 557 shares (> the 500 a uniform split
    // would give), demonstrating the front-loading end-to-end.
    var isStrategy = strategyRegistry.get("IS").orElseThrow();
    isStrategy.updateParameters(
        Map.of(
            "targetQuantity",
            1000,
            "side",
            "BUY",
            "startTime",
            "09:30",
            "endTime",
            "16:00",
            "numSlices",
            2,
            "urgency",
            0.5));
    router.setActiveStrategy("AMZN", "IS");

    var ts =
        ZonedDateTime.of(
                LocalDate.of(2026, 3, 24), LocalTime.of(11, 0), ZoneId.of("America/New_York"))
            .toInstant();
    var tick =
        new MarketTick(
            "AMZN",
            ts,
            EventType.QUOTE,
            BigDecimal.ZERO,
            0L,
            new BigDecimal("185.10"),
            new BigDecimal("185.20"),
            100L,
            80L,
            0L,
            DataSource.ALPACA,
            false);

    var props = new HashMap<String, Object>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-is-" + Instant.now().toEpochMilli());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    var captured = new AtomicReference<OrderSignal>();

    try (var consumer = new KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of("strategy.signals"));

      kafkaTemplate.send("market-data.ticks", "AMZN", objectMapper.writeValueAsString(tick)).get();

      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .until(
              () -> {
                var records = consumer.poll(Duration.ofMillis(200));
                for (var record : records) {
                  var signal = objectMapper.readValue(record.value(), OrderSignal.class);
                  if ("AMZN".equals(signal.symbol())) {
                    captured.set(signal);
                    return true;
                  }
                }
                return false;
              });
    }

    var signal = captured.get();
    assertThat(signal.symbol()).isEqualTo("AMZN");
    assertThat(signal.side()).isEqualTo(Side.BUY);
    assertThat(signal.quantity()).isEqualTo(557);
    assertThat(signal.strategyName()).isEqualTo("IS");
  }

  @Test
  void povTickPublishedToKafkaTriggersParticipationClip() throws Exception {
    // Configure POV: parent 1000 shares, 10% participation. A single TRADE tick of size 5000
    // means cumulativeMarketVolume=5000 → targetExecuted = 0.10 × 5000 = 500 → emit 500-share clip.
    var povStrategy = strategyRegistry.get("POV").orElseThrow();
    povStrategy.updateParameters(
        Map.of(
            "targetQuantity",
            1000,
            "side",
            "BUY",
            "startTime",
            "09:30",
            "endTime",
            "16:00",
            "participationRate",
            0.10,
            "minClipSize",
            1,
            "maxClipSize",
            Integer.MAX_VALUE));
    router.setActiveStrategy("TSLA", "POV");

    var ts =
        ZonedDateTime.of(
                LocalDate.of(2026, 3, 24), LocalTime.of(11, 0), ZoneId.of("America/New_York"))
            .toInstant();
    var tick =
        new MarketTick(
            "TSLA",
            ts,
            EventType.TRADE,
            new BigDecimal("245.30"),
            5_000L,
            new BigDecimal("245.28"),
            new BigDecimal("245.32"),
            100L,
            80L,
            0L,
            DataSource.ALPACA,
            false);

    var props = new HashMap<String, Object>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-pov-" + Instant.now().toEpochMilli());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    var captured = new AtomicReference<OrderSignal>();

    try (var consumer = new KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of("strategy.signals"));

      kafkaTemplate.send("market-data.ticks", "TSLA", objectMapper.writeValueAsString(tick)).get();

      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .until(
              () -> {
                var records = consumer.poll(Duration.ofMillis(200));
                for (var record : records) {
                  var signal = objectMapper.readValue(record.value(), OrderSignal.class);
                  if ("TSLA".equals(signal.symbol())) {
                    captured.set(signal);
                    return true;
                  }
                }
                return false;
              });
    }

    var signal = captured.get();
    assertThat(signal.symbol()).isEqualTo("TSLA");
    assertThat(signal.side()).isEqualTo(Side.BUY);
    assertThat(signal.quantity()).isEqualTo(500);
    assertThat(signal.strategyName()).isEqualTo("POV");
  }

  @Test
  void closePreCloseSliceLimitOrderEmittedDuringWorkingWindow() throws Exception {
    // Configure CLOSE: parent 1000 shares, 50% pre-close fraction, 5 slices. A tick inside the
    // first pre-close slice (15:05) emits the slice's 100-share LIMIT clip — exercising the
    // working-into-the-close phase end-to-end.
    var closeStrategy = strategyRegistry.get("CLOSE").orElseThrow();
    closeStrategy.updateParameters(
        Map.of(
            "targetQuantity",
            1000,
            "side",
            "BUY",
            "windowStart",
            "15:00",
            "closeTime",
            "16:00",
            "mocOffsetMinutes",
            10,
            "preCloseFraction",
            0.50,
            "numPreCloseSlices",
            5));
    router.setActiveStrategy("NVDA", "CLOSE");

    var ts =
        ZonedDateTime.of(
                LocalDate.of(2026, 3, 24), LocalTime.of(15, 5), ZoneId.of("America/New_York"))
            .toInstant();
    var tick =
        new MarketTick(
            "NVDA",
            ts,
            EventType.QUOTE,
            BigDecimal.ZERO,
            0L,
            new BigDecimal("875.20"),
            new BigDecimal("875.40"),
            100L,
            80L,
            0L,
            DataSource.ALPACA,
            false);

    var props = new HashMap<String, Object>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(
        ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-close-" + Instant.now().toEpochMilli());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    var captured = new AtomicReference<OrderSignal>();

    try (var consumer = new KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of("strategy.signals"));

      kafkaTemplate.send("market-data.ticks", "NVDA", objectMapper.writeValueAsString(tick)).get();

      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .until(
              () -> {
                var records = consumer.poll(Duration.ofMillis(200));
                for (var record : records) {
                  var signal = objectMapper.readValue(record.value(), OrderSignal.class);
                  if ("NVDA".equals(signal.symbol())) {
                    captured.set(signal);
                    return true;
                  }
                }
                return false;
              });
    }

    var signal = captured.get();
    assertThat(signal.symbol()).isEqualTo("NVDA");
    assertThat(signal.side()).isEqualTo(Side.BUY);
    assertThat(signal.quantity()).isEqualTo(100);
    assertThat(signal.strategyName()).isEqualTo("CLOSE");
  }

  @Test
  void momentumUptrendPublishedToKafkaTriggersEntrySignal() throws Exception {
    // Configure MOMENTUM with tiny EMA periods so a two-trade uptrend produces a bullish
    // crossover, and a 2-trade warmup so it acts immediately.
    var momentumStrategy = strategyRegistry.get("MOMENTUM").orElseThrow();
    momentumStrategy.updateParameters(
        Map.of(
            "fastPeriod", 2,
            "slowPeriod", 3,
            "warmupTrades", 2,
            "volumeMultiplier", 1.5,
            "tradeQuantity", 200,
            "side", "BUY"));
    router.setActiveStrategy("GOOGL", "MOMENTUM");

    var seed = googlTrade("155.00", 100L);
    var breakout = googlTrade("155.40", 300L);

    var props = new HashMap<String, Object>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(
        ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-momentum-" + Instant.now().toEpochMilli());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    var captured = new AtomicReference<OrderSignal>();

    try (var consumer = new KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of("strategy.signals"));

      // Ordered publish on the same key (symbol) → same partition → in-order processing.
      kafkaTemplate.send("market-data.ticks", "GOOGL", objectMapper.writeValueAsString(seed)).get();
      kafkaTemplate
          .send("market-data.ticks", "GOOGL", objectMapper.writeValueAsString(breakout))
          .get();

      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(500))
          .until(
              () -> {
                var records = consumer.poll(Duration.ofMillis(200));
                for (var record : records) {
                  var signal = objectMapper.readValue(record.value(), OrderSignal.class);
                  if ("GOOGL".equals(signal.symbol())) {
                    captured.set(signal);
                    return true;
                  }
                }
                return false;
              });
    }

    var signal = captured.get();
    assertThat(signal.symbol()).isEqualTo("GOOGL");
    assertThat(signal.side()).isEqualTo(Side.BUY);
    assertThat(signal.quantity()).isEqualTo(200);
    assertThat(signal.strategyName()).isEqualTo("MOMENTUM");
  }

  private static MarketTick googlTrade(String price, long size) {
    var ts =
        ZonedDateTime.of(
                LocalDate.of(2026, 3, 24), LocalTime.of(11, 0), ZoneId.of("America/New_York"))
            .toInstant();
    return new MarketTick(
        "GOOGL",
        ts,
        EventType.TRADE,
        new BigDecimal(price),
        size,
        new BigDecimal(price).subtract(new BigDecimal("0.02")),
        new BigDecimal(price).add(new BigDecimal("0.02")),
        100L,
        80L,
        0L,
        DataSource.ALPACA,
        false);
  }
}
