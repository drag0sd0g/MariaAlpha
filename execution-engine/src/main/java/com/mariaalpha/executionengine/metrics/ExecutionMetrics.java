package com.mariaalpha.executionengine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ExecutionMetrics {

  private final MeterRegistry registry;
  private final Timer orderLatencyTimer;
  private final Timer riskCheckTimer;
  private final Timer sorScoringTimer;

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
    this.sorScoringTimer =
        Timer.builder("mariaalpha.execution.sor.scoring.duration.ms")
            .description("Smart Order Router scoring loop duration")
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

  public void recordOrderSubmitted(String side, String type) {
    incrementCounter("mariaalpha.execution.orders.submitted.total", "side", side, "type", type);
  }

  public void recordFill(String symbol) {
    incrementCounter("mariaalpha.execution.fills.total", "symbol", symbol);
  }

  public void recordSorRouting(String venue, String venueType) {
    incrementCounter(
        "mariaalpha.execution.sor.routing.total", "venue", venue, "venue_type", venueType);
  }

  public void recordSorScoringDuration(long nanos) {
    sorScoringTimer.record(nanos, TimeUnit.NANOSECONDS);
  }

  public void recordSorCandidateScore(String venue, String venueType, double score) {
    DistributionSummary.builder("mariaalpha.execution.sor.candidate.score")
        .tag("venue", venue)
        .tag("venue_type", venueType)
        .baseUnit("score")
        .register(registry)
        .record(score);
  }

  public void recordVenueSubmit(String venue, String venueType) {
    incrementCounter(
        "mariaalpha.execution.venue.submit.total", "venue", venue, "venue_type", venueType);
  }

  public void recordVenueFill(String venue, String venueType) {
    incrementCounter(
        "mariaalpha.execution.venue.fills.total", "venue", venue, "venue_type", venueType);
  }

  public void recordIocResidualCancelled(String symbol, String side) {
    incrementCounter(
        "mariaalpha.execution.ioc.residual.cancelled.total", "symbol", symbol, "side", side);
  }

  public void recordFokKilled(String symbol, String side) {
    incrementCounter("mariaalpha.execution.fok.killed.total", "symbol", symbol, "side", side);
  }

  public void recordInternalCross(String symbol, boolean synthetic) {
    incrementCounter(
        "mariaalpha.execution.internal.crosses.total",
        "symbol",
        symbol,
        "synthetic",
        String.valueOf(synthetic));
  }

  public void recordInternalCrossedShares(String symbol, long shares) {
    Counter.builder("mariaalpha.execution.internal.shares.crossed.total")
        .tag("symbol", symbol)
        .register(registry)
        .increment(shares);
  }

  public void recordInternalSpreadCapturedBps(String symbol, double spreadBps) {
    DistributionSummary.builder("mariaalpha.execution.internal.spread.captured.bps")
        .tag("symbol", symbol)
        .baseUnit("bps")
        .register(registry)
        .record(spreadBps);
  }

  public <T> void registerInternalBookGauges(
      T source,
      java.util.function.ToIntFunction<T> buyDepth,
      java.util.function.ToIntFunction<T> sellDepth,
      java.util.function.ToIntFunction<T> restingOrders) {
    // Pass the live source bean (not a lambda capturing it) so Micrometer's WeakReference keeps a
    // strong handle as long as the bean is alive. Lambdas get GC'd shortly after register() and
    // the gauge degrades to NaN.
    io.micrometer.core.instrument.Gauge.builder(
            "mariaalpha.execution.internal.book.buy.depth", source, buyDepth::applyAsInt)
        .register(registry);
    io.micrometer.core.instrument.Gauge.builder(
            "mariaalpha.execution.internal.book.sell.depth", source, sellDepth::applyAsInt)
        .register(registry);
    io.micrometer.core.instrument.Gauge.builder(
            "mariaalpha.execution.internal.book.resting.orders", source, restingOrders::applyAsInt)
        .register(registry);
  }

  private void incrementCounter(String counterName, String tagName, String tagValue) {
    Counter.builder(counterName).tag(tagName, tagValue).register(registry).increment();
  }

  private void incrementCounter(
      String counterName,
      String firstTagName,
      String firstTagValue,
      String secondTagName,
      String secondTagValue) {
    Counter.builder(counterName)
        .tag(firstTagName, firstTagValue)
        .tag(secondTagName, secondTagValue)
        .register(registry)
        .increment();
  }
}
