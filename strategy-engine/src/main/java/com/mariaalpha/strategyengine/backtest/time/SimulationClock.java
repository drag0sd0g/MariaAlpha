package com.mariaalpha.strategyengine.backtest.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A mutable {@link Clock} whose "now" is driven by the backtest replay loop rather than the wall
 * clock. The engine advances it to each historical tick's timestamp so that any code reading time
 * through an injected {@link Clock} observes simulated time and produces deterministic,
 * reproducible results.
 *
 * <p>Views produced by {@link #withZone(ZoneId)} share the same underlying instant, so advancing
 * one advances all of them.
 */
public final class SimulationClock extends Clock {

  private final AtomicReference<Instant> current;
  private final ZoneId zone;

  public SimulationClock(Instant start) {
    this(new AtomicReference<>(Objects.requireNonNull(start, "start")), ZoneOffset.UTC);
  }

  private SimulationClock(AtomicReference<Instant> current, ZoneId zone) {
    this.current = current;
    this.zone = zone;
  }

  /** Advances simulated time to {@code instant}. Called once per replayed tick by the engine. */
  public void advanceTo(Instant instant) {
    current.set(Objects.requireNonNull(instant, "instant"));
  }

  @Override
  public Instant instant() {
    return current.get();
  }

  @Override
  public long millis() {
    return current.get().toEpochMilli();
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId newZone) {
    return new SimulationClock(current, Objects.requireNonNull(newZone, "newZone"));
  }
}
