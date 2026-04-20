package com.mariaalpha.ordermanager.metrics;

import com.mariaalpha.ordermanager.repository.PositionRepository;
import com.mariaalpha.ordermanager.service.PortfolioService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class OrderManagerMetrics {

  private final MeterRegistry registry;
  private final PositionRepository positionRepository;
  private final Timer orderPersistTimer;
  private final PortfolioService portfolioService;

  public OrderManagerMetrics(
      MeterRegistry registry,
      PositionRepository positionRepository,
      PortfolioService portfolioService) {
    this.registry = registry;
    this.positionRepository = positionRepository;
    this.portfolioService = portfolioService;
    this.orderPersistTimer =
        Timer.builder("mariaalpha.orders.persist.duration.ms")
            .description("Order persistence duration")
            .register(registry);
  }

  @PostConstruct
  void registerGauges() {
    Gauge.builder("mariaalpha.positions.count", positionRepository, repo -> (double) repo.count())
        .description("Number of tracked positions (including flat)")
        .register(registry);

    Gauge.builder(
            "mariaalpha.portfolio.total.pnl",
            portfolioService,
            service -> service.totalPnl().doubleValue())
        .description("Total P&L (realized + unrealized) across all positions")
        .register(registry);

    Gauge.builder(
            "mariaalpha.portfolio.gross.exposure",
            portfolioService,
            service -> service.grossExposure().doubleValue())
        .description("Gross exposure (sum of absolute notionals)")
        .register(registry);

    Gauge.builder(
            "mariaalpha.portfolio.net.exposure",
            portfolioService,
            service -> service.netExposure().doubleValue())
        .description("Net exposure (long minus short notional)")
        .register(registry);
  }

  public void recordOrderPersisted(String side) {
    incrementCounter("mariaalpha.orders.persisted.total", "side", side);
  }

  public void recordFillPersisted(String side, String venue) {
    Counter.builder("mariaalpha.fills.persisted.total")
        .tag("side", side)
        .tag("venue", venue)
        .register(registry)
        .increment();
  }

  public void recordOrderPersistDuration(long ms) {
    orderPersistTimer.record(Duration.ofMillis(ms));
  }

  private void incrementCounter(String name, String tagName, String tagValue) {
    Counter.builder(name).tag(tagName, tagValue).register(registry).increment();
  }
}
