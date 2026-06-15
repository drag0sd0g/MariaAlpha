package com.mariaalpha.executionengine.crossing;

import com.mariaalpha.executionengine.metrics.ExecutionMetrics;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("simulated")
public class InternalCrossingMetrics {

  private final InternalCrossingEngine engine;
  private final ExecutionMetrics metrics;

  public InternalCrossingMetrics(InternalCrossingEngine engine, ExecutionMetrics metrics) {
    this.engine = engine;
    this.metrics = metrics;
  }

  @PostConstruct
  void register() {
    engine.addCrossListener(this::onCross);
    metrics.registerInternalBookGauges(
        engine,
        e -> e.stats().restingOrdersBuy(),
        e -> e.stats().restingOrdersSell(),
        e -> e.stats().restingOrdersBuy() + e.stats().restingOrdersSell());
  }

  private void onCross(MidpointCross cross) {
    metrics.recordInternalCross(cross.symbol(), cross.synthetic());
    metrics.recordInternalCrossedShares(cross.symbol(), cross.quantity());
    metrics.recordInternalSpreadCapturedBps(cross.symbol(), cross.spreadBps().doubleValue());
  }
}
