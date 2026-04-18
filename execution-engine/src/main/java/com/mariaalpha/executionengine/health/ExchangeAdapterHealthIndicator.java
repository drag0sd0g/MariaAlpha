package com.mariaalpha.executionengine.health;

import com.mariaalpha.executionengine.adapter.ExchangeAdapter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class ExchangeAdapterHealthIndicator implements HealthIndicator {

  private final ExchangeAdapter adapter;

  public ExchangeAdapterHealthIndicator(ExchangeAdapter adapter) {
    this.adapter = adapter;
  }

  @Override
  public Health health() {
    if (adapter.isHealthy()) {
      return Health.up().withDetail("adapter", adapter.getClass().getSimpleName()).build();
    }
    return Health.down()
        .withDetail("adapter", adapter.getClass().getSimpleName())
        .withDetail("reason", "adapter reports unhealthy")
        .build();
  }
}
