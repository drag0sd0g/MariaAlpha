package com.mariaalpha.ordermanager.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.ordermanager.config.RedisConfig;
import com.mariaalpha.ordermanager.controller.dto.PositionSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisPositionCachePublisherTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOps;

  private ObjectMapper objectMapper;
  private SimpleMeterRegistry registry;
  private RedisPositionCachePublisher publisher;
  private final RedisConfig config =
      new RedisConfig(
          true, "mariaalpha:position:", "mariaalpha.positions.updates", Duration.ofHours(1));

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    registry = new SimpleMeterRegistry();
    publisher = new RedisPositionCachePublisher(redisTemplate, objectMapper, config, registry);
  }

  @Test
  void publishWritesValueAndPublishesPubSub() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    var snap = snapshot("AAPL", 100, 150);

    publisher.publish(snap);

    verify(valueOps)
        .set(eq("mariaalpha:position:AAPL"), any(String.class), eq(Duration.ofHours(1)));
    verify(redisTemplate).convertAndSend(eq("mariaalpha.positions.updates"), any(String.class));
    assertThat(registry.counter("mariaalpha_position_cache_writes_total").count()).isEqualTo(1.0);
  }

  @Test
  void publishSwallowsRedisFailuresAndRecordsMetric() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    doThrow(new QueryTimeoutException("redis down"))
        .when(valueOps)
        .set(any(), any(), any(Duration.class));

    publisher.publish(snapshot("MSFT", 50, 200));

    assertThat(registry.counter("mariaalpha_position_cache_write_failures_total").count())
        .isEqualTo(1.0);
  }

  @Test
  void publishDropsNullSymbol() {
    publisher.publish(null);
    publisher.publish(
        new PositionSnapshot(null, BigDecimal.ZERO, null, null, null, null, Instant.now()));

    verify(redisTemplate, never()).opsForValue();
    verify(redisTemplate, never()).convertAndSend(any(), any());
  }

  private static PositionSnapshot snapshot(String sym, int qty, int price) {
    return new PositionSnapshot(
        sym,
        BigDecimal.valueOf(qty),
        BigDecimal.valueOf(price),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.valueOf(price),
        Instant.parse("2026-06-02T12:00:00Z"));
  }
}
