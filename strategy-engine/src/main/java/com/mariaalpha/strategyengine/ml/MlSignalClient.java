package com.mariaalpha.strategyengine.ml;

import com.google.common.annotations.VisibleForTesting;
import com.mariaalpha.proto.signal.SignalRequest;
import com.mariaalpha.proto.signal.SignalServiceGrpc;
import com.mariaalpha.strategyengine.config.MlConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MlSignalClient {

  private static final Logger LOG = LoggerFactory.getLogger(MlSignalClient.class);
  private static final String CIRCUIT_BREAKER_NAME = "mlSignal";
  private static final long DEADLINE_MS = 500;

  private final ManagedChannel channel;
  private final SignalServiceGrpc.SignalServiceBlockingStub stub;
  private final CircuitBreaker circuitBreaker;

  @Autowired
  public MlSignalClient(MlConfig config, MeterRegistry meterRegistry) {
    this(
        ManagedChannelBuilder.forAddress(config.host(), config.port()).usePlaintext().build(),
        meterRegistry);
  }

  @VisibleForTesting
  MlSignalClient(ManagedChannel channel, MeterRegistry meterRegistry) {
    this.channel = channel;
    this.stub = SignalServiceGrpc.newBlockingStub(channel);
    this.circuitBreaker = buildCircuitBreaker();
    registerCircuitBreakerGauge(meterRegistry);
  }

  /**
   * Calls {@code GetSignal} on the ML service for the given symbol. Returns empty if the circuit
   * breaker is open, the call times out, or any other error occurs.
   */
  public Optional<MlSignalResult> getSignal(String symbol) {
    try {
      return circuitBreaker.executeSupplier(
          () -> {
            var request = SignalRequest.newBuilder().setSymbol(symbol).build();
            var response =
                stub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS).getSignal(request);
            return Optional.of(
                new MlSignalResult(response.getDirection(), response.getConfidence()));
          });
    } catch (CallNotPermittedException e) {
      LOG.debug("ML circuit breaker OPEN - skipping call for {}", symbol);
      return Optional.empty();
    } catch (Exception e) {
      LOG.warn("ML signal call failed for symbol {}: {}", symbol, e.getMessage());
      return Optional.empty();
    }
  }

  public CircuitBreaker.State getCircuitBreakerState() {
    return circuitBreaker.getState();
  }

  @PreDestroy
  void shutDown() {
    try {
      channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Interrupted while shutting down ML gRPC channel");
    }
  }

  private static CircuitBreaker buildCircuitBreaker() {
    return CircuitBreaker.of(
        CIRCUIT_BREAKER_NAME,
        CircuitBreakerConfig.custom()
            .slidingWindowSize(5)
            .failureRateThreshold(100)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(1)
            .build());
  }

  private void registerCircuitBreakerGauge(MeterRegistry meterRegistry) {
    Gauge.builder(
            "mariaalpha_strategy_ml_circuit_breaker_state",
            circuitBreaker,
            cb ->
                switch (cb.getState()) {
                  case CLOSED -> 0.0;
                  case HALF_OPEN -> 1.0;
                  case OPEN -> 2.0;
                  default -> -1.0;
                })
        .description("Circuit breaker state: 0 = CLOSED, 1 = HALF_OPEN, 2 = OPEN")
        .register(meterRegistry);
  }
}
