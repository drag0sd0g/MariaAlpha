package com.mariaalpha.executionengine.config;

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("alpaca")
public class ResilienceConfig {

  @Bean
  public CircuitBreaker alpacaCircuitBreaker() {
    return CircuitBreaker.of(
        "alpacaExchange",
        CircuitBreakerConfig.custom()
            .slidingWindowSize(5)
            .failureRateThreshold(60)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .permittedNumberOfCallsInHalfOpenState(1)
            .build());
  }

  @Bean
  public Retry alpacaRetry() {
    return Retry.of(
        "alpacaExchange",
        RetryConfig.custom()
            .maxAttempts(3)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(500, 2.0))
            .build());
  }

  @Bean
  public TimeLimiter alpacaTimeLimiter() {
    return TimeLimiter.of(
        "alpacaExchange",
        TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build());
  }

  @Bean
  public RateLimiter alpacaRateLimiter() {
    return RateLimiter.of(
        "alpacaApi",
        RateLimiterConfig.custom()
            .limitForPeriod(200)
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ofSeconds(10))
            .build());
  }

  @Bean
  public ThreadPoolBulkhead orderSubmissionBulkhead() {
    return buildThreadPoolBulkhead("orderSubmission", 10, 5, 50);
  }

  @Bean
  public ThreadPoolBulkhead fillProcessingBulkhead() {
    return buildThreadPoolBulkhead("fillProcessing", 5, 3, 100);
  }

  @Bean
  public ThreadPoolBulkhead riskCheckBulkhead() {
    return buildThreadPoolBulkhead("riskCheck", 5, 3, 50);
  }

  private ThreadPoolBulkhead buildThreadPoolBulkhead(
      String name, int maxThreadPoolSize, int coreThreadPoolSize, int queueCapacity) {
    return ThreadPoolBulkhead.of(
        name,
        ThreadPoolBulkheadConfig.custom()
            .maxThreadPoolSize(maxThreadPoolSize)
            .coreThreadPoolSize(coreThreadPoolSize)
            .queueCapacity(queueCapacity)
            .build());
  }
}
