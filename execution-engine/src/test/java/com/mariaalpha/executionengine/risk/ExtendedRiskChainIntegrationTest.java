package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.config.SymbolReferenceConfig;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.PositionTracker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
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
class ExtendedRiskChainIntegrationTest {

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

  @Autowired private RiskCheckChain chain;
  @Autowired private MarketStateTracker tracker;
  @Autowired private PositionTracker positions;
  @Autowired private SymbolReferenceData refData;
  @Autowired private SymbolReferenceConfig refConfig;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
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
  void referenceDataLoadedFromApplicationYml() {
    assertThat(refConfig.symbols()).isNotNull();
    assertThat(refData.sectorOf("AAPL")).isEqualTo("TECH");
    assertThat(refData.sectorOf("TSLA")).isEqualTo("AUTOMOTIVE");
    assertThat(refData.betaOf("NVDA")).isCloseTo(1.65, within());
    assertThat(refData.advOf("MSFT")).isEqualTo(25_000_000L);
  }

  @Test
  void chainIncludesExtendedChecks() {
    var names = chainCheckNames();
    assertThat(names).contains("SectorExposure", "BetaExposure", "AdvParticipation");
  }

  @Test
  void chainRejectsOrderBreachingAdvParticipation() {
    tracker.update(
        new MarketState(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            new BigDecimal("178.52"),
            Instant.now()));
    var order =
        new com.mariaalpha.executionengine.model.Order(
            new com.mariaalpha.executionengine.model.OrderSignal(
                "AAPL",
                com.mariaalpha.executionengine.model.Side.BUY,
                8_000_000,
                com.mariaalpha.executionengine.model.OrderType.MARKET,
                null,
                null,
                "T",
                Instant.now()));
    var result = chain.evaluate(order);
    assertThat(result.passed()).isFalse();
    assertThat(result.checkName())
        .as("expected a hard pre-trade reject, regardless of which check fires first")
        .isIn(
            "MaxOrderNotional",
            "MaxPositionPerSymbol",
            "MaxPortfolioExposure",
            "SectorExposure",
            "BetaExposure",
            "AdvParticipation");
  }

  @Test
  void chainPassesNormalOrderWhenLimitsAreCalibrated() {
    tracker.update(
        new MarketState(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            new BigDecimal("178.52"),
            Instant.now()));
    var order =
        new com.mariaalpha.executionengine.model.Order(
            new com.mariaalpha.executionengine.model.OrderSignal(
                "AAPL",
                com.mariaalpha.executionengine.model.Side.BUY,
                100,
                com.mariaalpha.executionengine.model.OrderType.MARKET,
                null,
                null,
                "T",
                Instant.now()));
    var result = chain.evaluate(order);
    assertThat(result.passed()).isTrue();
  }

  private Set<String> chainCheckNames() {
    try {
      var field = RiskCheckChain.class.getDeclaredField("checks");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var checks = (java.util.List<RiskCheck>) field.get(chain);
      return checks.stream().map(RiskCheck::name).collect(java.util.stream.Collectors.toSet());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static org.assertj.core.data.Offset<Double> within() {
    return org.assertj.core.data.Offset.offset(1e-6);
  }
}
