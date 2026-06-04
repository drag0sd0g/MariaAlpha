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

/**
 * VenueAdapter facade for the simulated internal crossing venue. Delegates the matching itself to
 * {@link InternalCrossingEngine} and translates {@link MidpointCross} events into {@link
 * ExecutionReport}s on the {@code OrderExecutionService} callback.
 *
 * <p>The adapter retains two probability knobs ({@code crossProbabilityOnSubmit} and {@code
 * matchProbabilityPerTick}) as <i>simulated-liquidity rates</i>: when no real counterparty is
 * resting on the opposite side, the adapter rolls a die and may ask the engine to synthesize one.
 * This keeps the simulator producing crosses even when only one strategy is feeding the venue,
 * while the real matching path remains primary.
 */
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

  /** Visible for tests — runs one sweep. Called every {@code tickIntervalMs} in prod. */
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
    // Dispatch the execution-report callback asynchronously so the caller of submitOrder() has a
    // chance to transition the order to SUBMITTED before the FILLED event arrives. All other
    // venue adapters follow the same async contract; OrderExecutionService relies on it.
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
