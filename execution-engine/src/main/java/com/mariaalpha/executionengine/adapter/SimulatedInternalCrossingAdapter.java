package com.mariaalpha.executionengine.adapter;

import com.mariaalpha.executionengine.config.InternalCrossingConfig;
import com.mariaalpha.executionengine.crossing.InternalCrossingEngine;
import com.mariaalpha.executionengine.crossing.MidpointCross;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.OrderAck;
import com.mariaalpha.executionengine.router.VenueType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Random;
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
  private final InternalCrossingEngine engine;
  private final ScheduledExecutorService scheduler;
  private final Random random;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile Consumer<ExecutionReport> reportCallback;

  public SimulatedInternalCrossingAdapter(
      InternalCrossingConfig config, InternalCrossingEngine engine) {
    this.config = config;
    this.engine = engine;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "internal-crossing");
              t.setDaemon(true);
              return t;
            });
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
    if (engine.totalResting() >= config.maxPending()) {
      return new OrderAck(order.getOrderId(), "", false, "internal crossing capacity exceeded");
    }
    var exchangeId = engine.submit(order);
    order.setExchangeOrderId(exchangeId);
    if (engine.isResting(exchangeId)
        && config.crossProbabilityOnSubmit() > 0.0
        && random.nextDouble() < config.crossProbabilityOnSubmit()) {
      scheduler.schedule(
          () -> engine.synthesizeCounterparty(exchangeId),
          Math.max(0, config.fillLatencyMs()),
          TimeUnit.MILLISECONDS);
    }
    return new OrderAck(order.getOrderId(), exchangeId, true, "");
  }

  @Override
  public OrderAck cancelOrder(String exchangeOrderId) {
    var ok = engine.cancel(exchangeOrderId);
    if (ok) {
      return new OrderAck("", exchangeOrderId, true, "cancelled");
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
    engine.addCrossListener(this::onCross);
    scheduler.scheduleAtFixedRate(
        this::matchTick, config.tickIntervalMs(), config.tickIntervalMs(), TimeUnit.MILLISECONDS);
    started.set(true);
    LOG.info(
        "SimulatedInternalCrossingAdapter started: venue={} tickMs={} synthSub={} synthTick={}",
        config.venue(),
        config.tickIntervalMs(),
        config.crossProbabilityOnSubmit(),
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
    return engine.totalResting();
  }

  public InternalCrossingEngine engine() {
    return engine;
  }

  void matchTick() {
    engine.sweep();
    if (config.matchProbabilityPerTick() <= 0.0) {
      return;
    }
    for (var symbol : engine.bookSnapshot().keySet()) {
      if (random.nextDouble() >= config.matchProbabilityPerTick()) {
        continue;
      }
      engine.firstRestingOrderId(symbol).ifPresent(engine::synthesizeCounterparty);
    }
  }

  private void onCross(MidpointCross cross) {
    if (scheduler.isShutdown()) {
      return;
    }
    long delay = Math.max(0, config.fillLatencyMs());
    scheduler.schedule(() -> dispatchCross(cross), delay, TimeUnit.MILLISECONDS);
  }

  private void dispatchCross(MidpointCross cross) {
    var cb = reportCallback;
    if (cb == null) {
      return;
    }
    cb.accept(toReport(cross.aggressorExchangeOrderId(), cross));
    if (!cross.synthetic() && cross.counterpartyExchangeOrderId() != null) {
      cb.accept(toReport(cross.counterpartyExchangeOrderId(), cross));
    }
  }

  private ExecutionReport toReport(String exchangeId, MidpointCross cross) {
    int remaining = engine.remainingFor(exchangeId);
    return new ExecutionReport(
        exchangeId,
        cross.midpoint(),
        cross.quantity(),
        remaining,
        config.venue(),
        cross.timestamp());
  }
}
