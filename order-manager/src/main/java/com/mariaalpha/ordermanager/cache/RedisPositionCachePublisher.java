package com.mariaalpha.ordermanager.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.ordermanager.config.RedisConfig;
import com.mariaalpha.ordermanager.controller.dto.PositionSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase-2 (issue 2.7.4) — writes the latest {@link PositionSnapshot} for each symbol into Redis
 * (key {@code mariaalpha:position:<symbol>}) for sub-millisecond cross-service reads, and publishes
 * a pub/sub event on {@code mariaalpha.positions.updates} so subscribers can invalidate any local
 * cache without polling. Redis remains a pure cache; PostgreSQL is the system of record. Failures
 * here never block fill processing — a warning is logged and processing continues.
 */
@Component
@ConditionalOnProperty(prefix = "order-manager.redis", name = "enabled", matchIfMissing = true)
public class RedisPositionCachePublisher {

  private static final Logger LOG = LoggerFactory.getLogger(RedisPositionCachePublisher.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final RedisConfig config;
  private final Counter writesTotal;
  private final Counter writeFailuresTotal;
  private final Timer writeLatency;

  public RedisPositionCachePublisher(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      RedisConfig config,
      MeterRegistry meterRegistry) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.config = config;
    this.writesTotal =
        Counter.builder("mariaalpha_position_cache_writes_total")
            .description("Successful position-cache writes to Redis")
            .register(meterRegistry);
    this.writeFailuresTotal =
        Counter.builder("mariaalpha_position_cache_write_failures_total")
            .description("Failed position-cache writes to Redis")
            .register(meterRegistry);
    this.writeLatency =
        Timer.builder("mariaalpha_position_cache_write_latency")
            .description("Latency of position-cache writes to Redis")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
  }

  public void publish(PositionSnapshot snapshot) {
    if (snapshot == null || snapshot.symbol() == null) {
      return;
    }
    long start = System.nanoTime();
    try {
      var payload = objectMapper.writeValueAsString(snapshot);
      var key = config.positionKeyPrefix() + snapshot.symbol();
      redisTemplate.opsForValue().set(key, payload, config.positionTtl());
      redisTemplate.convertAndSend(config.positionsPubSubChannel(), payload);
      writesTotal.increment();
    } catch (JsonProcessingException e) {
      LOG.warn(
          "Failed to serialize position snapshot for cache {}: {}",
          snapshot.symbol(),
          e.getMessage());
      writeFailuresTotal.increment();
    } catch (DataAccessException e) {
      // Never let Redis hiccups fail a fill — Kafka is the authoritative path.
      LOG.warn(
          "Redis cache write failed for {}: {}; continuing without cache update",
          snapshot.symbol(),
          e.getMessage());
      writeFailuresTotal.increment();
    } finally {
      writeLatency.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }
}
