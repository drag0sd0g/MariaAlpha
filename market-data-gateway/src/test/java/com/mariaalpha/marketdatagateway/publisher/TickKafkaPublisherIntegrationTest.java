package com.mariaalpha.marketdatagateway.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import com.mariaalpha.marketdatagateway.config.KafkaPublisherConfig;
import com.mariaalpha.marketdatagateway.model.BarTimeframe;
import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.EventType;
import com.mariaalpha.marketdatagateway.model.HistoricalBar;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Tag("integration")
@Testcontainers
class TickKafkaPublisherIntegrationTest {

  private static final String TOPIC = "market-data.ticks";

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final Sinks.Many<MarketTick> tickSink = Sinks.many().multicast().onBackpressureBuffer();

  private TickKafkaPublisher publisher;
  private KafkaConsumer<String, String> consumer;

  @BeforeEach
  void setUp() {
    var producerProps =
        Map.<String, Object>of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    var kafkaTemplate =
        new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, String>(producerProps));

    var config = new KafkaPublisherConfig(TOPIC);
    var adapter = stubAdapter();

    publisher = new TickKafkaPublisher(adapter, kafkaTemplate, objectMapper, config, meterRegistry);
    publisher.start();

    var consumerProps =
        Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "test-group",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
    consumer = new KafkaConsumer<>(consumerProps);
    consumer.subscribe(List.of(TOPIC));
  }

  @AfterEach
  void tearDown() {
    publisher.stop();
    consumer.close();
  }

  @Test
  void publishedTickIsConsumedFromKafka() throws Exception {
    var tick =
        new MarketTick(
            "AAPL",
            Instant.parse("2026-03-24T14:30:00.123Z"),
            EventType.TRADE,
            new BigDecimal("178.52"),
            100L,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0L,
            0L,
            12345678L,
            DataSource.ALPACA,
            false);

    tickSink.tryEmitNext(tick);

    var records = consumer.poll(Duration.ofSeconds(10));
    assertThat(records).isNotEmpty();

    var record = records.iterator().next();
    assertThat(record.key()).isEqualTo("AAPL");

    var receivedTick = objectMapper.readValue(record.value(), MarketTick.class);
    assertThat(receivedTick.symbol()).isEqualTo("AAPL");
    assertThat(receivedTick.eventType()).isEqualTo(EventType.TRADE);
    assertThat(receivedTick.price()).isEqualByComparingTo(new BigDecimal("178.52"));
    assertThat(receivedTick.size()).isEqualTo(100L);
    assertThat(receivedTick.timestamp()).isEqualTo(Instant.parse("2026-03-24T14:30:00.123Z"));
    assertThat(receivedTick.source()).isEqualTo(DataSource.ALPACA);
  }

  @Test
  void multipleTicksPublishedWithCorrectKeys() {
    tickSink.tryEmitNext(tradeTick("AAPL"));
    tickSink.tryEmitNext(tradeTick("MSFT"));
    tickSink.tryEmitNext(tradeTick("GOOGL"));

    var records = consumer.poll(Duration.ofSeconds(10));
    assertThat(records).hasSize(3);

    var keys = new ArrayList<String>();
    records.forEach(r -> keys.add(r.key()));
    assertThat(keys).containsExactlyInAnyOrder("AAPL", "MSFT", "GOOGL");
  }

  @Test
  void tickCounterMetricIncrements() {
    tickSink.tryEmitNext(tradeTick("AAPL"));
    tickSink.tryEmitNext(tradeTick("AAPL"));

    consumer.poll(Duration.ofSeconds(10));

    var counter =
        meterRegistry
            .find("mariaalpha_md_ticks_received_total")
            .tag("symbol", "AAPL")
            .tag("event_type", "TRADE")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(2.0);
  }

  private MarketDataAdapter stubAdapter() {
    return new MarketDataAdapter() {
      @Override
      public void connect(List<String> symbols) {}

      @Override
      public void disconnect() {}

      @Override
      public Flux<MarketTick> streamTicks() {
        return tickSink.asFlux();
      }

      @Override
      public List<HistoricalBar> getHistoricalBars(
          String symbol, LocalDate from, LocalDate to, BarTimeframe timeframe) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean isConnected() {
        return true;
      }

      @Override
      public List<String> subscribedSymbols() {
        return List.of();
      }
    };
  }

  private static MarketTick tradeTick(String symbol) {
    return new MarketTick(
        symbol,
        Instant.parse("2026-03-24T14:30:00.123Z"),
        EventType.TRADE,
        new BigDecimal("178.52"),
        100L,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0L,
        0L,
        0L,
        DataSource.ALPACA,
        false);
  }
}
