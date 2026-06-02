package com.mariaalpha.ordermanager.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.ordermanager.config.RedisConfig;
import com.mariaalpha.ordermanager.controller.dto.PositionSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase-2 (issue 2.7.4) integration test that exercises the publisher against a real Redis,
 * verifying the SET + TTL + PUBLISH path end-to-end.
 */
@Tag("integration")
@Testcontainers
class RedisPositionCacheIntegrationTest {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate template;

  @BeforeAll
  static void startContainer() {
    REDIS.start();
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
    connectionFactory.afterPropertiesSet();
    template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
  }

  @AfterAll
  static void stopContainer() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
    REDIS.stop();
  }

  @Test
  void publishWritesKeyAndTtlInRealRedis() {
    var config =
        new RedisConfig(
            true, "mariaalpha:position:", "mariaalpha.positions.updates", Duration.ofMinutes(30));
    var publisher =
        new RedisPositionCachePublisher(
            template,
            new ObjectMapper().registerModule(new JavaTimeModule()),
            config,
            new SimpleMeterRegistry());

    publisher.publish(
        new PositionSnapshot(
            "AAPL",
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(150),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.valueOf(155),
            Instant.parse("2026-06-02T12:00:00Z")));

    var raw = template.opsForValue().get("mariaalpha:position:AAPL");
    assertThat(raw).isNotNull().contains("\"symbol\":\"AAPL\"").contains("\"netQuantity\":100");

    var ttl = template.getExpire("mariaalpha:position:AAPL");
    assertThat(ttl).isGreaterThan(0L).isLessThanOrEqualTo(30L * 60L);
  }
}
