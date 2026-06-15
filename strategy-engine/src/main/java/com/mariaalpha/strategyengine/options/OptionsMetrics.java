package com.mariaalpha.strategyengine.options;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class OptionsMetrics {

  private static final String PRICINGS_TOTAL = "mariaalpha_options_pricings_total";
  private static final String PRICING_DURATION = "mariaalpha_options_pricing_duration";
  private static final String IMPLIED_VOL_ITERATIONS = "mariaalpha_options_implied_vol_iterations";
  private static final String IMPLIED_VOL_SOLVES_TOTAL =
      "mariaalpha_options_implied_vol_solves_total";

  private final MeterRegistry registry;

  public OptionsMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  void recordPricing(OptionType type, long durationNanos) {
    Counter.builder(PRICINGS_TOTAL)
        .description("Total Black-Scholes pricings executed")
        .tag("type", type.name())
        .register(registry)
        .increment();
    Timer.builder(PRICING_DURATION)
        .description("Black-Scholes pricing latency")
        .tag("type", type.name())
        .register(registry)
        .record(durationNanos, TimeUnit.NANOSECONDS);
  }

  void recordImpliedVolSolve(
      OptionType type, ImpliedVolatilityCalculator.Method method, int iters) {
    Counter.builder(IMPLIED_VOL_SOLVES_TOTAL)
        .description("Implied volatility solves by method")
        .tag("type", type.name())
        .tag("method", method.name())
        .register(registry)
        .increment();
    DistributionSummary.builder(IMPLIED_VOL_ITERATIONS)
        .description("Iterations consumed by the implied-vol solver before convergence")
        .tag("type", type.name())
        .tag("method", method.name())
        .register(registry)
        .record(iters);
  }
}
