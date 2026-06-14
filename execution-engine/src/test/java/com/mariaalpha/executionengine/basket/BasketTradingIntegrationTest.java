package com.mariaalpha.executionengine.basket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mariaalpha.executionengine.controller.dto.BasketLegRequest;
import com.mariaalpha.executionengine.controller.dto.BasketOrderRequest;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.BasketTradingService;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * End-to-end program/basket flow against the in-process simulated adapter and a Testcontainers
 * Kafka. Submits a two-leg basket and asserts that, as both legs fill, the basket aggregate
 * transitions to FILLED — exercising the {@code BasketTradingService} → pipeline → {@code
 * BasketCoordinator} loop.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("simulated")
@Tag("integration")
@DirtiesContext
class BasketTradingIntegrationTest {

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

  @Autowired private BasketTradingService service;
  @Autowired private MarketStateTracker tracker;

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
  void twoLegBasket_bothLegsFill_basketReachesFilled() {
    seed("AAPL", "149.50", "150.00");
    seed("MSFT", "304.50", "305.00");

    var view =
        service.submit(
            new BasketOrderRequest(
                "rebalance",
                List.of(
                    new BasketLegRequest(
                        "AAPL",
                        Side.BUY,
                        OrderType.LIMIT,
                        100,
                        new BigDecimal("151.00"),
                        null,
                        null),
                    new BasketLegRequest(
                        "MSFT",
                        Side.BUY,
                        OrderType.LIMIT,
                        50,
                        new BigDecimal("306.00"),
                        null,
                        null))));

    assertThat(view.totalLegs()).isEqualTo(2);
    assertThat(view.acceptedLegs()).isEqualTo(2);

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> service.get(view.basketId()).orElseThrow().status() == BasketStatus.FILLED);

    var filled = service.get(view.basketId()).orElseThrow();
    assertThat(filled.filledLegs()).isEqualTo(2);
    assertThat(filled.filledQuantity()).isEqualTo(150);
  }

  private void seed(String symbol, String bid, String ask) {
    tracker.update(
        new MarketState(
            symbol, new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(ask), Instant.now()));
  }
}
