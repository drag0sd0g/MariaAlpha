package com.mariaalpha.posttrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.posttrade.Application;
import com.mariaalpha.posttrade.model.DataSource;
import com.mariaalpha.posttrade.model.EventType;
import com.mariaalpha.posttrade.model.FillEvent;
import com.mariaalpha.posttrade.model.FillRecord;
import com.mariaalpha.posttrade.model.MarketTickEvent;
import com.mariaalpha.posttrade.model.OrderDetails;
import com.mariaalpha.posttrade.model.OrderLifecycleEvent;
import com.mariaalpha.posttrade.model.OrderSnapshotEvent;
import com.mariaalpha.posttrade.model.OrderStatus;
import com.mariaalpha.posttrade.model.OrderType;
import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.repository.ArrivalSnapshotRepository;
import com.mariaalpha.posttrade.repository.TcaResultRepository;
import com.mariaalpha.posttrade.service.OrderManagerClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "post-trade.tca.arrival-snapshot-max-lookback-seconds=3600",
      "logging.level.com.mariaalpha.posttrade=DEBUG"
    })
@Import(TcaE2eIT.TestBeans.class)
@ActiveProfiles("test")
class TcaE2eIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container @ServiceConnection
  static KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.1"));

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private TcaResultRepository tcaRepository;
  @Autowired private ArrivalSnapshotRepository arrivalRepository;
  @Autowired private ObjectMapper objectMapper;
  @MockBean private OrderManagerClient orderManagerClient;

  @Test
  void endToEnd_computesTcaForFilledOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    Instant orderTs = Instant.parse("2026-04-20T09:30:00Z");
    Instant fillTs = Instant.parse("2026-04-20T09:40:00Z");

    publish(
        "market-data.ticks",
        "AAPL",
        objectMapper.writeValueAsString(quote("AAPL", orderTs.minusSeconds(1), 179.98, 180.02)));
    publish(
        "market-data.ticks",
        "AAPL",
        objectMapper.writeValueAsString(trade("AAPL", orderTs.plusSeconds(60), 180.02, 500)));
    publish(
        "market-data.ticks",
        "AAPL",
        objectMapper.writeValueAsString(trade("AAPL", orderTs.plusSeconds(300), 180.04, 500)));

    publish(
        "orders.lifecycle",
        orderId.toString(),
        objectMapper.writeValueAsString(
            new OrderLifecycleEvent(
                orderId.toString(),
                OrderStatus.NEW,
                new OrderSnapshotEvent(
                    orderId.toString(),
                    "c-1",
                    "AAPL",
                    Side.BUY,
                    1000,
                    OrderType.MARKET,
                    null,
                    null,
                    "VWAP",
                    0,
                    null,
                    "x",
                    "ALPACA"),
                null,
                null,
                orderTs)));

    await().atMost(Duration.ofSeconds(20)).until(() -> arrivalRepository.existsByOrderId(orderId));

    when(orderManagerClient.fetchOrder(any(UUID.class)))
        .thenReturn(
            Optional.of(
                new OrderDetails(
                    orderId,
                    "c-1",
                    "AAPL",
                    Side.BUY,
                    OrderType.MARKET,
                    new BigDecimal("1000"),
                    null,
                    OrderStatus.FILLED,
                    "VWAP",
                    new BigDecimal("1000"),
                    new BigDecimal("180.05"),
                    "x",
                    "ALPACA",
                    orderTs,
                    fillTs,
                    List.of(
                        new FillRecord(
                            UUID.randomUUID(),
                            orderId,
                            "AAPL",
                            Side.BUY,
                            new BigDecimal("180.05"),
                            new BigDecimal("1000"),
                            new BigDecimal("5.00"),
                            "ALPACA",
                            "e1",
                            fillTs)))));

    publish(
        "orders.lifecycle",
        orderId.toString(),
        objectMapper.writeValueAsString(
            new OrderLifecycleEvent(
                orderId.toString(),
                OrderStatus.FILLED,
                new OrderSnapshotEvent(
                    orderId.toString(),
                    "c-1",
                    "AAPL",
                    Side.BUY,
                    1000,
                    OrderType.MARKET,
                    null,
                    null,
                    "VWAP",
                    1000,
                    new BigDecimal("180.05"),
                    "x",
                    "ALPACA"),
                new FillEvent(
                    "f1",
                    orderId.toString(),
                    "e1",
                    "AAPL",
                    Side.BUY,
                    new BigDecimal("180.05"),
                    1000,
                    new BigDecimal("5.00"),
                    "ALPACA",
                    fillTs),
                null,
                fillTs)));

    await().atMost(Duration.ofSeconds(30)).until(() -> tcaRepository.existsByOrderId(orderId));

    var tca = tcaRepository.findByOrderId(orderId).orElseThrow();
    assertThat(tca.getSlippageBps()).isNotNull();
    assertThat(tca.getImplShortfallBps()).isNotNull();
    assertThat(tca.getVwapBenchmarkBps()).isNotNull();
    assertThat(tca.getSpreadCostBps()).isNotNull();
  }

  private void publish(String topic, String key, String payload) throws Exception {
    kafkaTemplate.send(topic, key, payload).get();
  }

  private static MarketTickEvent trade(String symbol, Instant ts, double price, long size) {
    return new MarketTickEvent(
        symbol,
        ts,
        EventType.TRADE,
        BigDecimal.valueOf(price),
        size,
        null,
        null,
        null,
        null,
        null,
        DataSource.SIMULATED,
        false);
  }

  private static MarketTickEvent quote(String symbol, Instant ts, double bid, double ask) {
    return new MarketTickEvent(
        symbol,
        ts,
        EventType.QUOTE,
        null,
        null,
        BigDecimal.valueOf(bid),
        BigDecimal.valueOf(ask),
        100L,
        100L,
        null,
        DataSource.SIMULATED,
        false);
  }

  @TestConfiguration
  static class TestBeans {

    @Bean
    ConcurrentHashMap<String, String> capturedAnalyticsTca() {
      return new ConcurrentHashMap<>();
    }
  }
}
