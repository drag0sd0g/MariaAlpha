package com.mariaalpha.strategyengine.metrics;

import com.mariaalpha.strategyengine.model.Side;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class StrategyMetrics {

  private static final String SIGNALS_TOTAL = "mariaalpha_strategy_signals_total";
  private static final String EVAL_DURATION = "mariaalpha_strategy_evaluation_duration_ms";
  private static final String ML_LATENCY = "mariaalpha_strategy_ml_latency_ms";

  private final MeterRegistry meterRegistry;

  public StrategyMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordSignal(String strategyName, Side side) {
    Counter.builder(SIGNALS_TOTAL)
        .description("Total order signals emitted by the strategy engine")
        .tag("strategy", strategyName)
        .tag("direction", side.name())
        .register(meterRegistry)
        .increment();
  }

  public void recordEvaluationDuration(String strategyName, long durationMs) {
    Timer.builder(EVAL_DURATION)
        .description("Strategy evaluation duration from onTick to evaluate result")
        .tag("strategy", strategyName)
        .register(meterRegistry)
        .record(Duration.ofMillis(durationMs));
  }

  public void recordMlLatency(long durationMs) {
    Timer.builder(ML_LATENCY)
        .description("gRPC round-trip latency to the ML Signal Service")
        .register(meterRegistry)
        .record(Duration.ofMillis(durationMs));
  }
}
