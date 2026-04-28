package com.mariaalpha.posttrade.metrics;

import com.mariaalpha.posttrade.tca.TcaComputation;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class PostTradeMetrics {

  private static final String UNKNOWN_STRATEGY = "unknown";

  private final MeterRegistry registry;
  private final Timer computationTimer;

  public PostTradeMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.computationTimer =
        Timer.builder("mariaalpha.tca.computation.duration.ms")
            .description("TCA computation wall-clock duration")
            .register(registry);
  }

  public void recordTcaComputation(String strategy, TcaComputation result, Duration elapsed) {
    computationTimer.record(elapsed);
    String strategyTag = strategy == null ? UNKNOWN_STRATEGY : strategy;
    if (result.slippageBps() != null) {
      summary("mariaalpha.tca.slippage.bps", strategyTag)
          .record(result.slippageBps().doubleValue());
    }
    if (result.implShortfallBps() != null) {
      summary("mariaalpha.tca.impl.shortfall.bps", strategyTag)
          .record(result.implShortfallBps().doubleValue());
    }
    if (result.vwapBenchmarkBps() != null) {
      summary("mariaalpha.tca.vwap.benchmark.bps", strategyTag)
          .record(result.vwapBenchmarkBps().doubleValue());
    }
    if (result.spreadCostBps() != null) {
      summary("mariaalpha.tca.spread.cost.bps", strategyTag)
          .record(result.spreadCostBps().doubleValue());
    }
    counter("mariaalpha.tca.computations.total", strategyTag);
  }

  private DistributionSummary summary(String name, String strategy) {
    return DistributionSummary.builder(name).tag("strategy", strategy).register(registry);
  }

  private void counter(String name, String strategy) {
    registry.counter(name, "strategy", strategy).increment();
  }

  public static BigDecimal nullSafe(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
