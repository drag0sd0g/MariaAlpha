package com.mariaalpha.posttrade.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import com.mariaalpha.posttrade.Application;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Source;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Status;
import com.mariaalpha.posttrade.model.FillForReconRecord;
import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.repository.ReconciliationBreakRepository;
import com.mariaalpha.posttrade.repository.ReconciliationRunRepository;
import com.mariaalpha.posttrade.service.OrderManagerClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {Application.class, EodReconciliationIT.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "post-trade.recon.enabled=false",
      "post-trade.recon.mode=EXTERNAL",
      "post-trade.recon.price-tolerance-bps=1.0",
      "post-trade.recon.quantity-tolerance=0.001",
      "post-trade.recon.high-severity-notional=10000",
      "post-trade.recon.critical-severity-notional=100000",
      "post-trade.recon.alpaca.base-url=http://stub",
      "post-trade.recon.alpaca.api-key=stub-key",
      "post-trade.recon.alpaca.api-secret=stub-secret",
      "post-trade.recon.alpaca.http-timeout-ms=2000",
      "post-trade.recon.alpaca.activity-types=FILL",
      "logging.level.com.mariaalpha.posttrade=DEBUG"
    })
@ActiveProfiles("test")
class EodReconciliationIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.1"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  @Autowired private EodReconciliationService service;
  @Autowired private ReconciliationBreakRepository breakRepository;
  @Autowired private ReconciliationRunRepository runRepository;

  @MockitoBean private OrderManagerClient orderManagerClient;
  @Autowired private AtomicReference<List<ExternalFill>> stubExternal;

  @AfterEach
  void cleanup() {
    breakRepository.deleteAll();
    runRepository.deleteAll();
    stubExternal.set(List.of());
  }

  @Test
  void mirroredFillsProduceCleanRun() {
    LocalDate d = LocalDate.of(2026, 6, 1);
    var internal = internalFill("alpaca-1", "AAPL", "180.05", "100");
    when(orderManagerClient.fetchFillsForDate(d)).thenReturn(List.of(internal));
    stubExternal.set(List.of(extFromInternal(internal)));

    var run = service.runForDate(d, Source.MANUAL);

    assertThat(run.getStatus()).isEqualTo(Status.SUCCESS.name());
    assertThat(run.getBreaksCount()).isEqualTo(0);
    assertThat(breakRepository.findByReconDateOrderBySeverityDesc(d)).isEmpty();
    assertThat(runRepository.findByReconDate(d)).isPresent();
  }

  @Test
  void missingExternalFillProducesPersistedBreakAndKafkaAlert() throws Exception {
    LocalDate d = LocalDate.of(2026, 6, 2);
    when(orderManagerClient.fetchFillsForDate(d))
        .thenReturn(List.of(internalFill("alpaca-1", "AAPL", "180.05", "100")));
    stubExternal.set(List.of());

    try (var consumer = riskAlertConsumer()) {
      consumer.subscribe(List.of("analytics.risk-alerts"));

      service.runForDate(d, Source.MANUAL);

      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(
              () -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                boolean got = false;
                for (ConsumerRecord<String, String> r : records) {
                  if (r.value() != null && r.value().contains("RECON_BREAK")) {
                    got = true;
                  }
                }
                assertThat(got).as("recon break alert reached analytics.risk-alerts").isTrue();
              });
    }

    var breaks = breakRepository.findByReconDateOrderBySeverityDesc(d);
    assertThat(breaks).hasSize(1);
    assertThat(breaks.get(0).getBreakType()).isEqualTo("EXTRA_FILL");
    assertThat(breaks.get(0).getSymbol()).isEqualTo("AAPL");
  }

  @Test
  void rerunningSameDateOverwritesPriorBreaks() {
    LocalDate d = LocalDate.of(2026, 6, 3);
    when(orderManagerClient.fetchFillsForDate(d))
        .thenReturn(List.of(internalFill("alpaca-1", "AAPL", "180.05", "100")));
    stubExternal.set(List.of());
    service.runForDate(d, Source.MANUAL);
    assertThat(breakRepository.findByReconDateOrderBySeverityDesc(d)).hasSize(1);

    stubExternal.set(List.of(extFromInternal(internalFill("alpaca-1", "AAPL", "180.05", "100"))));
    when(orderManagerClient.fetchFillsForDate(d))
        .thenReturn(List.of(internalFill("alpaca-1", "AAPL", "180.05", "100")));
    var rerun = service.runForDate(d, Source.MANUAL);

    assertThat(rerun.getBreaksCount()).isEqualTo(0);
    assertThat(breakRepository.findByReconDateOrderBySeverityDesc(d)).isEmpty();
    assertThat(runRepository.findByReconDate(d)).isPresent();
  }

  @Test
  void alpacaFailureMarksRunFailedNoBreaksRecorded() {
    LocalDate d = LocalDate.of(2026, 6, 4);
    when(orderManagerClient.fetchFillsForDate(d)).thenReturn(List.of());

    stubExternal.set(null);

    var run = service.runForDate(d, Source.SCHEDULED);

    assertThat(run.getStatus()).isEqualTo(Status.FAILED.name());
    assertThat(run.getErrorMessage()).isNotNull();
    assertThat(breakRepository.findByReconDateOrderBySeverityDesc(d)).isEmpty();
    assertThat(runRepository.findByReconDate(d)).isPresent();
  }

  private KafkaConsumer<String, String> riskAlertConsumer() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "recon-it-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    return new KafkaConsumer<>(props);
  }

  private static FillForReconRecord internalFill(
      String exchangeOrderId, String symbol, String price, String qty) {
    return new FillForReconRecord(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        exchangeOrderId,
        symbol,
        Side.BUY,
        new BigDecimal(price),
        new BigDecimal(qty),
        BigDecimal.ZERO,
        "PRIMARY",
        "fill-1",
        Instant.parse("2026-06-01T15:30:00Z"));
  }

  private static ExternalFill extFromInternal(FillForReconRecord f) {
    return new ExternalFill(
        f.fillId().toString(),
        f.exchangeOrderId(),
        f.clientOrderId(),
        f.symbol(),
        f.side(),
        f.fillPrice(),
        f.fillQuantity(),
        f.filledAt());
  }

  @Configuration
  static class TestConfig {

    @Bean
    AtomicReference<List<ExternalFill>> stubExternal() {
      return new AtomicReference<>(List.of());
    }

    @Bean
    @Primary
    AlpacaActivitiesClient stubAlpacaActivitiesClient(AtomicReference<List<ExternalFill>> stub) {
      return (date, internal) -> {
        List<ExternalFill> v = stub.get();
        if (v == null) {
          throw new AlpacaActivitiesException("simulated alpaca failure");
        }
        return v;
      };
    }
  }
}
