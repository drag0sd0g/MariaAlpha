package com.mariaalpha.executionengine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ExecutionMetrics {

  private final MeterRegistry registry;
  private final Timer orderLatencyTimer;
  private final Timer riskCheckTimer;

  public ExecutionMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.orderLatencyTimer =
        Timer.builder("mariaalpha.execution.order.latency.ms")
            .description("End-to-end order execution latency")
            .register(registry);
    this.riskCheckTimer =
        Timer.builder("mariaalpha.execution.risk.check.duration.ms")
            .description("Risk check chain evaluation duration")
            .register(registry);
  }

  public void recordOrderLatency(long ms) {
    orderLatencyTimer.record(Duration.ofMillis(ms));
  }

  public void recordRejection(String reason) {
    incrementCounter("mariaalpha.execution.risk.rejections.total", "reason", reason);
  }

  public void recordOrderSubmitted(String side) {
    incrementCounter("mariaalpha.execution.orders.submitted.total", "side", side);
  }

  public void recordFill(String symbol) {
    incrementCounter("mariaalpha.execution.fills.total", "symbol", symbol);
  }

  private void incrementCounter(String counterName, String tagName, String tagValue) {
    Counter.builder(counterName).tag(tagName, tagValue).register(registry).increment();
  }
}
