package com.mariaalpha.marketdatagateway.health;

import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

@Component("tick")
public class TickReadinessIndicator implements HealthIndicator {

  private static final Logger LOG = LoggerFactory.getLogger(TickReadinessIndicator.class);

  private final MarketDataAdapter adapter;
  private final AtomicBoolean tickReceived = new AtomicBoolean(false);
  private volatile Disposable subscription;

  public TickReadinessIndicator(MarketDataAdapter adapter) {
    this.adapter = adapter;
  }

  @PostConstruct
  void start() {
    subscription =
        adapter
            .streamTicks()
            .next()
            .subscribe(
                tick -> {
                  tickReceived.set(true);
                  LOG.info("First tick received ({}), marking ready", tick.symbol());
                });
  }

  @PreDestroy
  void stop() {
    if (subscription != null && !subscription.isDisposed()) {
      subscription.dispose();
    }
  }

  @Override
  public Health health() {
    if (tickReceived.get()) {
      return Health.up().withDetail("firstTickReceived", true).build();
    }
    return Health.outOfService().withDetail("firstTickReceived", false).build();
  }

  boolean isReady() {
    return tickReceived.get();
  }
}
