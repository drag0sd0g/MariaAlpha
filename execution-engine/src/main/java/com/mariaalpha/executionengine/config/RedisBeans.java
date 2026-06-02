package com.mariaalpha.executionengine.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Provides the pub/sub listener container the cache client subscribes through. */
@Configuration
@ConditionalOnProperty(prefix = "execution-engine.redis", name = "enabled", matchIfMissing = true)
public class RedisBeans {

  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory) {
    var container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    return container;
  }
}
