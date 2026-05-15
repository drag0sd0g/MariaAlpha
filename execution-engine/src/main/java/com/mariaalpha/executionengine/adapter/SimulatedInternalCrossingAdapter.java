package com.mariaalpha.executionengine.adapter;

import com.mariaalpha.executionengine.config.InternalCrossingConfig;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderAck;
import com.mariaalpha.executionengine.router.VenueType;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("simulated")
public class SimulatedInternalCrossingAdapter implements VenueAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(SimulatedInternalCrossingAdapter.class);

  private final InternalCrossingConfig config;
  private final MarketStateTracker marketStateTracker;
  private final ScheduledExecutorService scheduler;
  private final ConcurrentHashMap<String, Pending> pending;
  private final Random random;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile Consumer<ExecutionReport> reportCallback;

  public SimulatedInternalCrossingAdapter(
      InternalCrossingConfig config, MarketStateTracker marketStateTracker) {
    this.config = config;
    this.marketStateTracker = marketStateTracker;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "internal-crossing");
              t.setDaemon(true);
              return t;
            });
    this.pending = new ConcurrentHashMap<>();
    this.random = config.seed() >= 0 ? new Random(config.seed()) : new Random();
  }

  @Override
  public String venueName() {
    return config.venue();
  }

  @Override
  public VenueType venueType() {
    return VenueType.INTERNAL;
  }

  @Override
  public OrderAck submitOrder(ExecutionInstruction instruction) {
    var order = instruction.order();
    if (pending.size() >= config.maxPending()) {
      return new OrderAck(order.getOrderId(), "", false, "internal crossing capacity exceeded");
    }
    var exchangeId = "INT-" + UUID.randomUUID().toString().substring(0, 8);
    order.setExchangeOrderId(exchangeId);

    var market = marketStateTracker.getMarketState(order.getSymbol());
    if (market != null && random.nextDouble() < config.crossProbabilityOnSubmit()) {
      scheduler.schedule(
          () -> tryFill(exchangeId, order), config.fillLatencyMs(), TimeUnit.MILLISECONDS);
    } else {
      pending.put(exchangeId, new Pending(instruction, Instant.now()));
    }
    return new OrderAck(order.getOrderId(), exchangeId, true, "");
  }

  @Override
  public OrderAck cancelOrder(String exchangeOrderId) {
    var removed = pending.remove(exchangeOrderId);
    if (removed != null) {
      return new OrderAck(
          removed.instruction.order().getOrderId(), exchangeOrderId, true, "cancelled");
    }
    return new OrderAck("", exchangeOrderId, false, "order not found or already filled");
  }

  @Override
  public void onExecutionReport(Consumer<ExecutionReport> callback) {
    this.reportCallback = callback;
  }

  @PostConstruct
  @Override
  public void start() {
    scheduler.scheduleAtFixedRate(
        this::matchTick, config.tickIntervalMs(), config.tickIntervalMs(), TimeUnit.MILLISECONDS);
    started.set(true);
    LOG.info(
        "SimulatedInternalCrossingAdapter started: venue={} crossProb={} tickMs={} matchProb={}",
        config.venue(),
        config.crossProbabilityOnSubmit(),
        config.tickIntervalMs(),
        config.matchProbabilityPerTick());
  }

  @Override
  @PreDestroy
  public void shutdown() {
    started.set(false);
    scheduler.shutdownNow();
  }

  @Override
  public boolean isHealthy() {
    return started.get() && !scheduler.isShutdown();
  }

  public int pendingSize() {
    return pending.size();
  }

  void matchTick() {
    if (reportCallback == null || pending.isEmpty()) {
      return;
    }
    pending.forEach(
        (id, p) -> {
          if (random.nextDouble() < config.matchProbabilityPerTick()) {
            tryFill(id, p.instruction.order());
          }
        });
  }

  private void tryFill(String exchangeId, Order order) {
    if (reportCallback == null) {
      return;
    }
    var market = marketStateTracker.getMarketState(order.getSymbol());
    if (market == null || market.bidPrice() == null || market.askPrice() == null) {
      // No market data — leave pending and try later.
      pending.putIfAbsent(exchangeId, new Pending(null, Instant.now()));
      return;
    }
    var remaining = order.getRemainingQuantity();
    if (remaining <= 0) {
      pending.remove(exchangeId);
      return;
    }
    var midpoint =
        market
            .bidPrice()
            .add(market.askPrice())
            .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    pending.remove(exchangeId);
    var report =
        new ExecutionReport(exchangeId, midpoint, remaining, 0, config.venue(), Instant.now());
    LOG.info(
        "INTERNAL fill: {} {} {} @ {}", order.getSide(), order.getSymbol(), remaining, midpoint);
    reportCallback.accept(report);
  }

  private record Pending(ExecutionInstruction instruction, Instant submittedAt) {}
}
