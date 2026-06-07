package com.mariaalpha.executionengine.pegged;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
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
 * End-to-end PEGGED parent → child-fill flow against the in-process simulated venue adapter and a
 * Testcontainers Kafka (Kafka is required so the full execution-engine context wires up, even
 * though the pegged endpoints themselves don't publish).
 *
 * <p>Two scenarios:
 *
 * <ol>
 *   <li>{@code parentFillsOnFirstChild} — happy path: NBBO is stable, the first LIMIT child fills,
 *       the parent transitions through PARTIALLY_FILLED to FILLED.
 *   <li>{@code parentRepegsOnNbboMove} — NBBO moves past the configured re-peg threshold; the
 *       coordinator cancels the active child and submits a fresh one at the new pegged price.
 * </ol>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("simulated")
@Tag("integration")
@DirtiesContext
class PeggedCoordinatorIntegrationTest {

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

  @Autowired private OrderExecutionService service;
  @Autowired private MarketStateTracker tracker;
  @Autowired private PeggedRegistry registry;
  @Autowired private OrderLifecycleManager lifecycleManager;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("execution-engine.simulated.fill-latency-ms", () -> 5);
    registry.add("execution-engine.redis.enabled", () -> "false");
    registry.add("management.health.redis.enabled", () -> "false");
    registry.add(
        "spring.autoconfigure.exclude",
        () ->
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis"
                + ".RedisRepositoriesAutoConfiguration");
  }

  @Test
  void parentFillsOnFirstChild() {
    seedMarket("AAPL", "149.50", "150.00");
    var parent = createParent("AAPL", Side.BUY, 100, PegType.MIDPOINT, 0);

    service.submitOrder(parent);

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> parent.getStatus() == OrderStatus.FILLED);

    assertThat(parent.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(registry.progress(parent.getOrderId())).isEmpty();
  }

  @Test
  void parentRepegsOnNbboMove() {
    // Use SELL side with the PRIMARY peg so the simulator won't immediately cross — we want the
    // order to rest at the ask until we move the market, then re-peg.
    seedMarket("MSFT", "415.00", "415.20");
    var parent = createParent("MSFT", Side.SELL, 50, PegType.PRIMARY, 0);

    service.submitOrder(parent);

    // First child registers
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () ->
                registry
                    .progress(parent.getOrderId())
                    .map(p -> p.activeChildOrderId() != null)
                    .orElse(false));
    var beforeRepeg = registry.progress(parent.getOrderId()).orElseThrow();
    assertThat(beforeRepeg.repegsTotal()).isZero();

    // Move the NBBO ask by ~30 bps (415.20 → 416.40) — well above the 5 bps repeg threshold
    seedMarket("MSFT", "416.20", "416.40");

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> registry.progress(parent.getOrderId()).orElseThrow().repegsTotal() >= 1);

    var afterRepeg = registry.progress(parent.getOrderId()).orElseThrow();
    assertThat(afterRepeg.repegsTotal()).isGreaterThanOrEqualTo(1);
    assertThat(afterRepeg.lastSubmittedPrice()).isEqualByComparingTo("416.40");
  }

  private void seedMarket(String symbol, String bid, String ask) {
    tracker.update(
        new MarketState(
            symbol, new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(bid), Instant.now()));
  }

  private Order createParent(
      String symbol, Side side, int quantity, PegType pegType, Integer offsetBps) {
    var signal =
        new OrderSignal(
            symbol,
            side,
            quantity,
            OrderType.PEGGED,
            null,
            null,
            "MANUAL",
            Instant.now(),
            null,
            null,
            null,
            pegType,
            offsetBps);
    return new Order(signal);
  }
}
