package com.mariaalpha.executionengine.basket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class BasketMetrics {

  private final MeterRegistry registry;
  private final Map<String, Long> basketStartNanos = new ConcurrentHashMap<>();

  public BasketMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordBasketSubmitted(String basketId, int legCount) {
    basketStartNanos.put(basketId, System.nanoTime());
    Counter.builder("mariaalpha.execution.basket.submitted.total").register(registry).increment();
    Counter.builder("mariaalpha.execution.basket.legs.total")
        .register(registry)
        .increment(legCount);
  }

  public void recordLegRejected(String symbol) {
    Counter.builder("mariaalpha.execution.basket.legs.rejected.total")
        .tag("symbol", symbol)
        .register(registry)
        .increment();
  }

  public void recordLegFilled(String symbol, String side) {
    Counter.builder("mariaalpha.execution.basket.legs.filled.total")
        .tag("symbol", symbol)
        .tag("side", side)
        .register(registry)
        .increment();
  }

  public void recordBasketFilled(String basketId) {
    var start = basketStartNanos.remove(basketId);
    if (start != null) {
      Timer.builder("mariaalpha.execution.basket.duration.ms")
          .register(registry)
          .record(Duration.ofNanos(System.nanoTime() - start));
    }
  }
}
