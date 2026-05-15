package com.mariaalpha.executionengine.adapter;

import com.mariaalpha.executionengine.config.DarkPoolConfig;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.MarketState;
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
public class SimulatedDarkPoolAdapter implements VenueAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(SimulatedDarkPoolAdapter.class);

  private final DarkPoolConfig config;
  private final MarketStateTracker marketStateTracker;
  private final ScheduledExecutorService scheduler;
  private final ConcurrentHashMap<String, Pending> pending;
  private final Random random;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile Consumer<ExecutionReport> reportCallback;

  public SimulatedDarkPoolAdapter(DarkPoolConfig config, MarketStateTracker marketStateTracker) {
    this.config = config;
    this.marketStateTracker = marketStateTracker;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "dark-pool-matcher");
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
    return VenueType.DARK;
  }

  @Override
  public OrderAck submitOrder(ExecutionInstruction instruction) {
    var order = instruction.order();
    if (pending.size() >= config.maxPending()) {
      return new OrderAck(order.getOrderId(), "", false, "dark pool capacity exceeded");
    }
    var exchangeId = "DARK-" + UUID.randomUUID().toString().substring(0, 8);
    order.setExchangeOrderId(exchangeId);
    pending.put(exchangeId, new Pending(instruction, Instant.now()));
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
        "SimulatedDarkPoolAdapter started: venue={} tickMs={} matchProb={} minSpreadBps={}",
        config.venue(),
        config.tickIntervalMs(),
        config.matchProbabilityPerTick(),
        config.minSpreadBps());
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
          var order = p.instruction.order();
          var market = marketStateTracker.getMarketState(order.getSymbol());
          if (!eligible(market)) {
            return;
          }
          if (random.nextDouble() >= config.matchProbabilityPerTick()) {
            return;
          }
          fill(id, order, market);
        });
  }

  private boolean eligible(MarketState marketState) {
    if (marketState == null || marketState.bidPrice() == null || marketState.askPrice() == null) {
      return false;
    }
    if (marketState.bidPrice().signum() <= 0
        || marketState.askPrice().compareTo(marketState.bidPrice()) <= 0) {
      return false;
    }
    var mid =
        marketState
            .bidPrice()
            .add(marketState.askPrice())
            .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    var spreadBps =
        marketState
            .askPrice()
            .subtract(marketState.bidPrice())
            .divide(mid, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(10_000));
    return spreadBps.compareTo(BigDecimal.valueOf(config.minSpreadBps())) >= 0;
  }

  private void fill(String exchangeId, Order order, MarketState marketState) {
    var remaining = order.getRemainingQuantity();
    if (remaining <= 0) {
      pending.remove(exchangeId);
      return;
    }
    var fillQty = Math.max(1, (int) Math.ceil(remaining * config.partialFillRatio()));
    fillQty = Math.min(fillQty, remaining);
    var midPoint =
        marketState
            .bidPrice()
            .add(marketState.askPrice())
            .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    var newRemaining = remaining - fillQty;
    if (newRemaining == 0) {
      pending.remove(exchangeId);
    }
    var report =
        new ExecutionReport(
            exchangeId, midPoint, fillQty, newRemaining, config.venue(), Instant.now());
    LOG.info(
        "DARK fill: {} {} {} @ {} (remaining={})",
        order.getSide(),
        order.getSymbol(),
        fillQty,
        midPoint,
        newRemaining);
    reportCallback.accept(report);
  }

  private record Pending(ExecutionInstruction instruction, Instant submittedAt) {}
}
