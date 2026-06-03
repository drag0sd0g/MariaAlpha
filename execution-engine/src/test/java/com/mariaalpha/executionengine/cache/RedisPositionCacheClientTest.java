package com.mariaalpha.executionengine.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.executionengine.config.RedisConfig;
import com.mariaalpha.executionengine.service.PositionTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

@ExtendWith(MockitoExtension.class)
class RedisPositionCacheClientTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOps;
  @Mock private RedisMessageListenerContainer listenerContainer;

  private ObjectMapper objectMapper;
  private SimpleMeterRegistry registry;
  private PositionTracker tracker;
  private RedisPositionCacheClient client;
  private final RedisConfig config =
      new RedisConfig(true, "mariaalpha:position:", "mariaalpha.positions.updates");

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    registry = new SimpleMeterRegistry();
    tracker = new PositionTracker();
    client =
        new RedisPositionCacheClient(
            redisTemplate, listenerContainer, objectMapper, config, tracker, registry);
  }

  @Test
  void warmFromRedisSeedsTrackerFromAllKeys() {
    when(redisTemplate.keys("mariaalpha:position:*"))
        .thenReturn(Set.of("mariaalpha:position:AAPL", "mariaalpha:position:MSFT"));
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("mariaalpha:position:AAPL")).thenReturn(snapshotJson("AAPL", 100, 150));
    when(valueOps.get("mariaalpha:position:MSFT")).thenReturn(snapshotJson("MSFT", 50, 200));

    client.warmFromRedis();

    assertThat(tracker.getPositionNotional("AAPL")).isEqualByComparingTo("15000");
    assertThat(tracker.getPositionNotional("MSFT")).isEqualByComparingTo("10000");
  }

  @Test
  void fetchReturnsSnapshotOnHit() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("mariaalpha:position:TSLA")).thenReturn(snapshotJson("TSLA", -25, 800));

    var snap = client.fetch("TSLA");

    assertThat(snap).isNotNull();
    assertThat(snap.symbol()).isEqualTo("TSLA");
    assertThat(snap.netQuantity()).isEqualByComparingTo("-25");
    assertThat(registry.counter("mariaalpha_position_cache_hits_total").count()).isEqualTo(1.0);
  }

  @Test
  void fetchRecordsMissWhenKeyAbsent() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(anyString())).thenReturn(null);

    var snap = client.fetch("NVDA");

    assertThat(snap).isNull();
    assertThat(registry.counter("mariaalpha_position_cache_misses_total").count()).isEqualTo(1.0);
  }

  @Test
  void fetchSwallowsRedisErrors() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(anyString())).thenThrow(new QueryTimeoutException("timeout"));

    var snap = client.fetch("AMZN");

    assertThat(snap).isNull();
    assertThat(registry.counter("mariaalpha_position_cache_read_failures_total").count())
        .isEqualTo(1.0);
  }

  @Test
  void applyMessageUpdatesTrackerAndIncrementsCounter() {
    client.applyMessage(snapshotJson("AAPL", 100, 150));

    assertThat(tracker.getPositionNotional("AAPL")).isEqualByComparingTo("15000");
    assertThat(registry.counter("mariaalpha_position_cache_pubsub_updates_total").count())
        .isEqualTo(1.0);
  }

  @Test
  void applyMessageIsRobustToMalformedPayload() {
    client.applyMessage("not json");
    client.applyMessage("");
    client.applyMessage(null);
    // No exception escapes; tracker still empty.
    assertThat(tracker.getPositionNotional("AAPL")).isEqualByComparingTo("0");
  }

  @Test
  void subscribeRegistersWithListenerContainer() {
    client.subscribe();
    verify(listenerContainer).addMessageListener(any(MessageListener.class), any(Topic.class));
  }

  private String snapshotJson(String sym, int qty, int price) {
    return String.format(
        "{\"symbol\":\"%s\",\"netQuantity\":%d,\"avgEntryPrice\":%d,\"realizedPnl\":0,"
            + "\"unrealizedPnl\":0,\"lastMarkPrice\":%d,\"timestamp\":\"%s\"}",
        sym, qty, price, price, Instant.parse("2026-06-02T12:00:00Z"));
  }
}
