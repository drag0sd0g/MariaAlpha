package com.mariaalpha.executionengine.health;

import com.mariaalpha.executionengine.adapter.SimulatedDarkPoolAdapter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("darkPool")
@Profile("simulated")
public class DarkPoolHealthIndicator implements HealthIndicator {

  private final SimulatedDarkPoolAdapter adapter;

  public DarkPoolHealthIndicator(SimulatedDarkPoolAdapter adapter) {
    this.adapter = adapter;
  }

  @Override
  public Health health() {
    var currHealthBuilder = adapter.isHealthy() ? Health.up() : Health.down();
    return currHealthBuilder
        .withDetail("venue", adapter.venueName())
        .withDetail("pending", adapter.pendingSize())
        .build();
  }
}
