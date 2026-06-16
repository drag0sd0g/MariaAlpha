package com.mariaalpha.strategyengine.metrics;

import com.mariaalpha.strategyengine.ml.MlGateDecision;
import com.mariaalpha.strategyengine.model.Side;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class StrategyMetrics {

  private static final String SIGNALS_TOTAL = "mariaalpha_strategy_signals_total";
  private static final String EVAL_DURATION = "mariaalpha_strategy_evaluation_duration_ms";
  private static final String ML_LATENCY = "mariaalpha_strategy_ml_latency_ms";
  private static final String ML_DECISIONS_TOTAL = "mariaalpha_strategy_ml_decisions_total";
  private static final String ML_QTY_SCALE = "mariaalpha_strategy_ml_quantity_scale";
  private static final String TICKS_SUPPRESSED = "mariaalpha_strategy_ticks_suppressed_total";

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

  public void recordMlDecision(MlGateDecision.Outcome outcome, String strategyName, Side side) {
    Counter.builder(ML_DECISIONS_TOTAL)
        .description("ML signal gate decisions (confirm / veto / pass-through)")
        .tag("outcome", outcome.name())
        .tag("strategy", strategyName)
        .tag("side", side.name())
        .register(meterRegistry)
        .increment();
  }

  public void recordMlQuantityScale(String strategyName, double scale) {
    DistributionSummary.builder(ML_QTY_SCALE)
        .description("Distribution of quantity scale factors applied after ML confirmation")
        .tag("strategy", strategyName)
        .register(meterRegistry)
        .record(scale);
  }

  public void recordTickSuppressed(String symbol, String reason) {
    Counter.builder(TICKS_SUPPRESSED)
        .description("Ticks dropped before reaching a strategy, by reason")
        .tag("symbol", symbol)
        .tag("reason", reason)
        .register(meterRegistry)
        .increment();
  }
}
