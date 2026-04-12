package com.mariaalpha.strategyengine.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.strategyengine.ml.MlSignalClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class MlSignalHealthIndicatorTest {

  @Test
  void healthIsUpWhenCircuitClosed() {
    var client = mock(MlSignalClient.class);
    when(client.getCircuitBreakerState()).thenReturn(CircuitBreaker.State.CLOSED);
    assertThat(new MlSignalHealthIndicator(client).health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void healthIsDownWhenCircuitOpen() {
    var client = mock(MlSignalClient.class);
    when(client.getCircuitBreakerState()).thenReturn(CircuitBreaker.State.OPEN);
    assertThat(new MlSignalHealthIndicator(client).health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void healthIsUnknownWhenCircuitHalfOpen() {
    var client = mock(MlSignalClient.class);
    when(client.getCircuitBreakerState()).thenReturn(CircuitBreaker.State.HALF_OPEN);
    assertThat(new MlSignalHealthIndicator(client).health().getStatus()).isEqualTo(Status.UNKNOWN);
  }
}
