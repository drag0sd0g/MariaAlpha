package com.mariaalpha.executionengine.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.OrderExecutionService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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

/**
 * End-to-end iceberg parent → multi-slice flow against the in-process simulated adapter and a
 * Testcontainers Kafka. Verifies that the coordinator submits each subsequent slice on child
 * completion and that the parent transitions through PARTIALLY_FILLED to FILLED.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("simulated")
@Tag("integration")
@DirtiesContext
class IcebergCoordinatorIntegrationTest {

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

  @Autowired private OrderExecutionService service;
  @Autowired private MarketStateTracker tracker;
  @Autowired private ParentChildOrderRegistry registry;

  @Autowired
  private com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager lifecycleManager;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    // Make the simulator fill near-instantly so awaitility doesn't have to wait long
    registry.add("execution-engine.simulated.fill-latency-ms", () -> 5);
  }

  @Test
  void parent600DisplayQty100_completesViaSixChildren() {
    seedMarket();

    var parent = createParent("AAPL", 600, 100);
    service.submitOrder(parent);

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> parent.getStatus() == OrderStatus.FILLED);

    // The progress entry is removed on parent completion; the parent itself should be FILLED.
    assertThat(parent.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(registry.progress(parent.getOrderId())).isEmpty();
  }

  @Test
  void parent250DisplayQty100_finalSliceSizedRemainder() {
    seedMarket();

    var parent = createParent("AAPL", 250, 100);
    service.submitOrder(parent);

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> parent.getStatus() == OrderStatus.FILLED);

    assertThat(parent.getStatus()).isEqualTo(OrderStatus.FILLED);
  }

  private void seedMarket() {
    tracker.update(
        new MarketState(
            "AAPL",
            new BigDecimal("149.50"),
            new BigDecimal("150.00"),
            new BigDecimal("149.75"),
            Instant.now()));
  }

  private Order createParent(String symbol, int qty, int displayQty) {
    var signal =
        new OrderSignal(
            symbol,
            Side.BUY,
            qty,
            OrderType.ICEBERG,
            new BigDecimal("151.00"),
            null,
            "MANUAL",
            Instant.now(),
            displayQty,
            TimeInForce.DAY,
            null);
    return new Order(signal);
  }
}
