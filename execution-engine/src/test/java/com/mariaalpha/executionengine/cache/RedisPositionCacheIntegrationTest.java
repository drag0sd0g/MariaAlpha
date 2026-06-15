package com.mariaalpha.executionengine.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.executionengine.config.RedisConfig;
import com.mariaalpha.executionengine.service.PositionTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
class RedisPositionCacheIntegrationTest {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate template;
  private static RedisMessageListenerContainer listenerContainer;

  @BeforeAll
  static void startContainer() {
    REDIS.start();
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
    connectionFactory.afterPropertiesSet();
    template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
    listenerContainer = new RedisMessageListenerContainer();
    listenerContainer.setConnectionFactory(connectionFactory);
    listenerContainer.afterPropertiesSet();
    listenerContainer.start();
  }

  @AfterAll
  static void stopContainer() throws Exception {
    if (listenerContainer != null) {
      listenerContainer.stop();
      listenerContainer.destroy();
    }
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
    REDIS.stop();
  }

  @AfterEach
  void cleanRedis() {
    var keys = template.keys("mariaalpha:position:*");
    if (keys != null && !keys.isEmpty()) {
      template.delete(keys);
    }
  }

  @Test
  void warmUpSeedsTrackerFromExistingKeys() {
    template
        .opsForValue()
        .set(
            "mariaalpha:position:AAPL",
            "{\"symbol\":\"AAPL\",\"netQuantity\":100,\"avgEntryPrice\":150,"
                + "\"realizedPnl\":0,\"unrealizedPnl\":0,\"lastMarkPrice\":155}");

    var tracker = new PositionTracker();
    var client = newClient(tracker);
    client.start();

    assertThat(tracker.getPositionNotional("AAPL")).isEqualByComparingTo("15500");
    client.stop();
  }

  @Test
  void pubSubMessagesUpdateTrackerInRealTime() {
    var tracker = new PositionTracker();
    var client = newClient(tracker);
    client.start();

    template.convertAndSend(
        "mariaalpha.positions.updates",
        "{\"symbol\":\"MSFT\",\"netQuantity\":50,\"avgEntryPrice\":200,"
            + "\"realizedPnl\":0,\"unrealizedPnl\":0,\"lastMarkPrice\":210}");

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(tracker.getPositionNotional("MSFT")).isEqualByComparingTo("10500"));
    client.stop();
  }

  @Test
  void fetchReadsLiveValueFromRedis() {
    template
        .opsForValue()
        .set(
            "mariaalpha:position:GOOG",
            "{\"symbol\":\"GOOG\",\"netQuantity\":-10,\"avgEntryPrice\":2700,"
                + "\"realizedPnl\":0,\"unrealizedPnl\":0,\"lastMarkPrice\":2800}");

    var tracker = new PositionTracker();
    var client = newClient(tracker);

    var snap = client.fetch("GOOG");
    assertThat(snap).isNotNull();
    assertThat(snap.notional()).isEqualByComparingTo("-28000");
  }

  private RedisPositionCacheClient newClient(PositionTracker tracker) {
    return new RedisPositionCacheClient(
        template,
        listenerContainer,
        new ObjectMapper().registerModule(new JavaTimeModule()),
        new RedisConfig(true, "mariaalpha:position:", "mariaalpha.positions.updates"),
        tracker,
        new SimpleMeterRegistry());
  }
}
