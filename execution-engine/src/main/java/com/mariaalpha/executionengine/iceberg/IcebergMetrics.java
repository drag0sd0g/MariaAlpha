package com.mariaalpha.executionengine.iceberg;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class IcebergMetrics {

  private final MeterRegistry registry;
  private final Map<String, Long> parentStartNanos = new ConcurrentHashMap<>();

  public IcebergMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordParentSubmitted(String symbol) {
    parentStartNanos.put(symbol, System.nanoTime());
  }

  public void recordSliceSubmitted(String symbol, String side) {
    Counter.builder("mariaalpha.execution.iceberg.slices.total")
        .tag("parent_symbol", symbol)
        .tag("side", side)
        .register(registry)
        .increment();
  }

  public void recordParentFilled(String symbol) {
    var start = parentStartNanos.remove(symbol);
    if (start != null) {
      Timer.builder("mariaalpha.execution.iceberg.parent.duration.ms")
          .tag("parent_symbol", symbol)
          .register(registry)
          .record(Duration.ofNanos(System.nanoTime() - start));
    }
  }
}
