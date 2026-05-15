package com.mariaalpha.executionengine.health;

import com.mariaalpha.executionengine.adapter.SimulatedInternalCrossingAdapter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("internalCrossing")
@Profile("simulated")
public class InternalCrossingHealthIndicator implements HealthIndicator {

  private final SimulatedInternalCrossingAdapter adapter;

  public InternalCrossingHealthIndicator(SimulatedInternalCrossingAdapter adapter) {
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
