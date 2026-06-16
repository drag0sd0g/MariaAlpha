package com.mariaalpha.posttrade.recon;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ReconMetrics {

  private final MeterRegistry registry;
  private final Timer runDuration;

  public ReconMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.runDuration =
        Timer.builder("mariaalpha.recon.duration.seconds")
            .description("Wall-clock duration of EOD reconciliation runs")
            .register(registry);
  }

  public void recordBreak(String breakType, String severity) {
    registry
        .counter("mariaalpha.recon.breaks.total", "break_type", breakType, "severity", severity)
        .increment();
  }

  public void recordRun(String status, String source, Duration elapsed) {
    registry.counter("mariaalpha.recon.runs.total", "status", status, "source", source).increment();
    runDuration.record(elapsed);
  }
}
