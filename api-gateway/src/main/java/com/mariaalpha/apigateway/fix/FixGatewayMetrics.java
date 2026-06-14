package com.mariaalpha.apigateway.fix;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counters for the inbound FIX gateway, scraped by Alloy at the api-gateway metrics endpoint. */
@Component
public class FixGatewayMetrics {

  private final Counter accepted;
  private final Counter rejected;
  private final Counter cancelled;

  public FixGatewayMetrics(MeterRegistry registry) {
    this.accepted =
        Counter.builder("mariaalpha.fix.orders.total")
            .tag("outcome", "accepted")
            .register(registry);
    this.rejected =
        Counter.builder("mariaalpha.fix.orders.total")
            .tag("outcome", "rejected")
            .register(registry);
    this.cancelled = Counter.builder("mariaalpha.fix.cancels.total").register(registry);
  }

  public void recordAccepted() {
    accepted.increment();
  }

  public void recordRejected() {
    rejected.increment();
  }

  public void recordCancelled() {
    cancelled.increment();
  }
}
