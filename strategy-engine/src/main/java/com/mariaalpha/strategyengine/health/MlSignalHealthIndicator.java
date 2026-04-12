package com.mariaalpha.strategyengine.health;

import com.mariaalpha.strategyengine.ml.MlSignalClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("mlSignal")
public class MlSignalHealthIndicator implements HealthIndicator {

  private final MlSignalClient mlSignalClient;

  public MlSignalHealthIndicator(MlSignalClient mlSignalClient) {
    this.mlSignalClient = mlSignalClient;
  }

  @Override
  public Health health() {
    var state = mlSignalClient.getCircuitBreakerState();
    return switch (state) {
      case CLOSED ->
          Health.up().withDetail("circuitBreaker", CircuitBreaker.State.CLOSED.name()).build();
      case HALF_OPEN ->
          Health.unknown()
              .withDetail("circuitBreaker", CircuitBreaker.State.HALF_OPEN.name())
              .build();
      case OPEN ->
          Health.down().withDetail("circuitBreaker", CircuitBreaker.State.OPEN.name()).build();
      default -> Health.unknown().withDetail("circuitBreaker", state.name()).build();
    };
  }
}
