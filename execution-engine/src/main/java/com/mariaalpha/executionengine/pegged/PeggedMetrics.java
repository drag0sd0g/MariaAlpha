package com.mariaalpha.executionengine.pegged;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Per-component instrumentation for the pegged-order module (roadmap 3.2.3). */
@Component
public class PeggedMetrics {

  private static final String PARENTS_SUBMITTED_TOTAL =
      "mariaalpha_execution_pegged_parents_submitted_total";
  private static final String PARENTS_FILLED_TOTAL =
      "mariaalpha_execution_pegged_parents_filled_total";
  private static final String CHILDREN_SUBMITTED_TOTAL =
      "mariaalpha_execution_pegged_children_submitted_total";
  private static final String REPEGS_TOTAL = "mariaalpha_execution_pegged_repegs_total";

  private final MeterRegistry registry;

  public PeggedMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordParentSubmitted(String symbol, String pegType) {
    Counter.builder(PARENTS_SUBMITTED_TOTAL)
        .description("PEGGED parent orders accepted")
        .tag("symbol", symbol)
        .tag("pegType", pegType)
        .register(registry)
        .increment();
  }

  public void recordChildSubmitted(String symbol, String pegType) {
    Counter.builder(CHILDREN_SUBMITTED_TOTAL)
        .description("LIMIT children submitted on behalf of a PEGGED parent")
        .tag("symbol", symbol)
        .tag("pegType", pegType)
        .register(registry)
        .increment();
  }

  public void recordRepeg(String symbol, String pegType) {
    Counter.builder(REPEGS_TOTAL)
        .description("Cancel-and-resubmit cycles triggered by NBBO movement")
        .tag("symbol", symbol)
        .tag("pegType", pegType)
        .register(registry)
        .increment();
  }

  public void recordParentFilled(String symbol, String pegType) {
    Counter.builder(PARENTS_FILLED_TOTAL)
        .description("PEGGED parent orders fully filled")
        .tag("symbol", symbol)
        .tag("pegType", pegType)
        .register(registry)
        .increment();
  }
}
