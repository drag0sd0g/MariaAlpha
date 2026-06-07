package com.mariaalpha.posttrade.allocation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Per-component instrumentation for the trade-allocation engine (roadmap 3.4.2). */
@Component
public class AllocationMetrics {

  private static final String RUNS_TOTAL = "mariaalpha_post_trade_allocation_runs_total";
  private static final String ALLOCATIONS_TOTAL =
      "mariaalpha_post_trade_allocation_allocations_total";
  private static final String SHARES_ALLOCATED =
      "mariaalpha_post_trade_allocation_shares_allocated";

  private final MeterRegistry registry;

  public AllocationMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordRun(String symbol, AllocationMethod method, int allocationCount, double totalShares) {
    Counter.builder(RUNS_TOTAL)
        .description("Total allocation runs")
        .tag("symbol", symbol)
        .tag("method", method.name())
        .register(registry)
        .increment();
    Counter.builder(ALLOCATIONS_TOTAL)
        .description("Total per-sub-account allocations emitted")
        .tag("symbol", symbol)
        .tag("method", method.name())
        .register(registry)
        .increment(allocationCount);
    DistributionSummary.builder(SHARES_ALLOCATED)
        .description("Shares allocated per run")
        .tag("symbol", symbol)
        .tag("method", method.name())
        .register(registry)
        .record(totalShares);
  }
}
