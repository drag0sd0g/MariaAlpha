package com.mariaalpha.executionengine.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.config.RedisConfig;
import com.mariaalpha.executionengine.service.PositionTracker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "execution-engine.redis", name = "enabled", matchIfMissing = true)
public class RedisPositionCacheClient {

  private static final Logger LOG = LoggerFactory.getLogger(RedisPositionCacheClient.class);

  private final StringRedisTemplate redisTemplate;
  private final RedisMessageListenerContainer listenerContainer;
  private final ObjectMapper objectMapper;
  private final RedisConfig config;
  private final PositionTracker positionTracker;
  private final Counter hitsTotal;
  private final Counter missesTotal;
  private final Counter pubSubUpdatesTotal;
  private final Counter readFailuresTotal;
  private final Timer readLatency;

  private MessageListener pubSubListener;

  public RedisPositionCacheClient(
      StringRedisTemplate redisTemplate,
      RedisMessageListenerContainer listenerContainer,
      ObjectMapper objectMapper,
      RedisConfig config,
      PositionTracker positionTracker,
      MeterRegistry meterRegistry) {
    this.redisTemplate = redisTemplate;
    this.listenerContainer = listenerContainer;
    this.objectMapper = objectMapper;
    this.config = config;
    this.positionTracker = positionTracker;
    this.hitsTotal =
        Counter.builder("mariaalpha_position_cache_hits_total")
            .description("Redis position-cache reads that returned a snapshot")
            .register(meterRegistry);
    this.missesTotal =
        Counter.builder("mariaalpha_position_cache_misses_total")
            .description("Redis position-cache reads that returned no snapshot")
            .register(meterRegistry);
    this.pubSubUpdatesTotal =
        Counter.builder("mariaalpha_position_cache_pubsub_updates_total")
            .description("Position-cache pub/sub messages applied to the in-memory tracker")
            .register(meterRegistry);
    this.readFailuresTotal =
        Counter.builder("mariaalpha_position_cache_read_failures_total")
            .description("Redis position-cache reads that failed with an error")
            .register(meterRegistry);
    this.readLatency =
        Timer.builder("mariaalpha_position_cache_read_latency")
            .description("Latency of Redis position-cache reads")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
  }

  @PostConstruct
  public void start() {
    warmFromRedis();
    subscribe();
  }

  @PreDestroy
  public void stop() {
    if (pubSubListener != null) {
      try {
        listenerContainer.removeMessageListener(pubSubListener);
      } catch (RuntimeException e) {
        LOG.warn("Failed to remove Redis pub/sub listener: {}", e.getMessage());
      }
    }
  }

  public PositionSnapshot fetch(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      return null;
    }
    long start = System.nanoTime();
    try {
      var payload = redisTemplate.opsForValue().get(config.positionKeyPrefix() + symbol);
      if (payload == null) {
        missesTotal.increment();
        return null;
      }
      var snapshot = objectMapper.readValue(payload, PositionSnapshot.class);
      hitsTotal.increment();
      return snapshot;
    } catch (DataAccessException e) {
      LOG.warn("Redis position cache read failed for {}: {}", symbol, e.getMessage());
      readFailuresTotal.increment();
      return null;
    } catch (Exception e) {
      LOG.warn("Failed to deserialize cached position for {}: {}", symbol, e.getMessage());
      readFailuresTotal.increment();
      return null;
    } finally {
      readLatency.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  void warmFromRedis() {
    try {
      Set<String> keys = redisTemplate.keys(config.positionKeyPrefix() + "*");
      if (keys == null || keys.isEmpty()) {
        LOG.info(
            "Redis position cache: no warm-up keys found under '{}'", config.positionKeyPrefix());
        return;
      }
      int seeded = 0;
      for (String key : keys) {
        var payload = redisTemplate.opsForValue().get(key);
        if (payload == null) {
          continue;
        }
        try {
          var snapshot = objectMapper.readValue(payload, PositionSnapshot.class);
          if (snapshot != null && snapshot.symbol() != null) {
            positionTracker.updatePosition(snapshot.symbol(), snapshot.notional());
            seeded++;
          }
        } catch (Exception e) {
          LOG.warn("Skipping malformed cached position at key {}: {}", key, e.getMessage());
        }
      }
      LOG.info("Redis position cache: warmed {} symbol(s) from Redis", seeded);
    } catch (DataAccessException e) {
      LOG.warn(
          "Redis position cache warm-up failed: {}; continuing with empty in-memory tracker",
          e.getMessage());
      readFailuresTotal.increment();
    }
  }

  void subscribe() {
    try {
      pubSubListener = new PubSubHandler();
      Topic topic = new ChannelTopic(config.positionsPubSubChannel());
      listenerContainer.addMessageListener(pubSubListener, topic);
      LOG.info("Subscribed to Redis pub/sub channel '{}'", config.positionsPubSubChannel());
    } catch (RuntimeException e) {
      LOG.warn(
          "Failed to subscribe to Redis pub/sub channel '{}': {}; pub/sub updates will be skipped",
          config.positionsPubSubChannel(),
          e.getMessage());
    }
  }

  void applyMessage(String payload) {
    if (payload == null || payload.isBlank()) {
      return;
    }
    try {
      var snapshot = objectMapper.readValue(payload, PositionSnapshot.class);
      if (snapshot != null && snapshot.symbol() != null) {
        positionTracker.updatePosition(snapshot.symbol(), snapshot.notional());
        pubSubUpdatesTotal.increment();
      }
    } catch (Exception e) {
      LOG.warn("Failed to apply pub/sub position update: {}", e.getMessage());
    }
  }

  private final class PubSubHandler implements MessageListener {
    @Override
    public void onMessage(Message message, byte[] pattern) {
      applyMessage(new String(message.getBody(), StandardCharsets.UTF_8));
    }
  }
}
