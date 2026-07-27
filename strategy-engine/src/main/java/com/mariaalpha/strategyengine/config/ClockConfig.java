package com.mariaalpha.strategyengine.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the application-wide {@link Clock}. Production code injects this system clock; tests and
 * the backtest harness substitute a fixed or simulated clock so time-dependent behaviour is
 * deterministic.
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
