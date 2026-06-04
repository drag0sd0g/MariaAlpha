package com.mariaalpha.executionengine.crossing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mariaalpha.executionengine.adapter.SimulatedInternalCrossingAdapter;
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
import org.junit.jupiter.api.BeforeEach;
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
 * Wiring test for the internal crossing engine. Verifies that when orders are submitted directly to
 * the simulated internal-crossing venue adapter, the embedded {@link InternalCrossingEngine}
 * matches them and both legs flow through {@link OrderExecutionService}'s execution-report callback
 * into the {@link OrderLifecycleManager} as FILLED.
 *
 * <p>Submitting via the SOR isn't deterministic (other venues outscore INTERNAL_CROSS on the
 * stock-simulated config), so this test calls the adapter directly to keep the assertion sharp. The
 * SOR-routed path is covered by the e2e test in {@code e2e-tests}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("simulated")
@Tag("integration")
@DirtiesContext
class InternalCrossingIntegrationTest {

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

  @Autowired private OrderExecutionService service;
  @Autowired private MarketStateTracker tracker;
  @Autowired private SimulatedInternalCrossingAdapter internalAdapter;
  @Autowired private InternalCrossingEngine engine;
  @Autowired private OrderLifecycleManager lifecycle;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("execution-engine.internal-crossing.cross-probability-on-submit", () -> 0.0);
    registry.add("execution-engine.internal-crossing.match-probability-per-tick", () -> 0.0);
    registry.add("execution-engine.internal-crossing.seed", () -> 7L);
    registry.add("execution-engine.redis.enabled", () -> "false");
    registry.add("management.health.redis.enabled", () -> "false");
    registry.add(
        "spring.autoconfigure.exclude",
        () ->
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis"
                + ".RedisRepositoriesAutoConfiguration");
  }

  @BeforeEach
  void primeMarket() {
    tracker.update(
        new MarketState(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            new BigDecimal("178.52"),
            Instant.now()));
  }

  @Test
  void offsettingInterestCrossesAndBothLegsReachFilledStatus() {
    long before = engine.stats().crossesTotal();

    var sell =
        new Order(
            new OrderSignal(
                "AAPL", Side.SELL, 100, OrderType.MARKET, null, null, "T-SELL", Instant.now()));
    var buy =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "T-BUY", Instant.now()));

    // Submit through OrderExecutionService.submitOrder so they're registered with the lifecycle
    // manager. We then manually re-dispatch through the internal adapter to force the venue
    // choice — the normal SOR path is exercised in the e2e test.
    service.submitOrder(sell);
    service.submitOrder(buy);

    // Direct hits on the internal adapter — these create *additional* orders that go straight to
    // the engine and cross there. Pre-registering with the lifecycle manager would require
    // hand-wiring, so we instead assert against the engine + venue-adapter health.
    var sellOnly =
        new Order(
            new OrderSignal(
                "AAPL", Side.SELL, 50, OrderType.MARKET, null, null, "T-SELL-D", Instant.now()));
    var buyOnly =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, 50, OrderType.MARKET, null, null, "T-BUY-D", Instant.now()));
    lifecycle.registerOrder(sellOnly);
    lifecycle.registerOrder(buyOnly);

    var sellInstr =
        new com.mariaalpha.executionengine.model.ExecutionInstruction(
            sellOnly, com.mariaalpha.executionengine.model.TimeInForce.DAY, null);
    var buyInstr =
        new com.mariaalpha.executionengine.model.ExecutionInstruction(
            buyOnly, com.mariaalpha.executionengine.model.TimeInForce.DAY, null);
    var sellAck = internalAdapter.submitOrder(sellInstr);
    sellOnly.setExchangeOrderId(sellAck.exchangeOrderId());
    lifecycle.transition(sellOnly.getOrderId(), OrderStatus.SUBMITTED, null, null);
    var buyAck = internalAdapter.submitOrder(buyInstr);
    buyOnly.setExchangeOrderId(buyAck.exchangeOrderId());
    lifecycle.transition(buyOnly.getOrderId(), OrderStatus.SUBMITTED, null, null);

    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> engine.stats().crossesTotal() > before);

    assertThat(lifecycle.getOrder(sellOnly.getOrderId()).getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(lifecycle.getOrder(buyOnly.getOrderId()).getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(lifecycle.getOrder(sellOnly.getOrderId()).getAvgFillPrice())
        .isEqualByComparingTo("178.52");
    assertThat(lifecycle.getOrder(buyOnly.getOrderId()).getAvgFillPrice())
        .isEqualByComparingTo("178.52");

    var stats = engine.stats();
    assertThat(stats.internalCrossesTotal()).isGreaterThanOrEqualTo(1);
    assertThat(stats.syntheticCrossesTotal()).isZero();
    assertThat(stats.sharesCrossedTotal()).isGreaterThanOrEqualTo(50);
    assertThat(stats.spreadCapturedNotional()).isPositive();
  }
}
