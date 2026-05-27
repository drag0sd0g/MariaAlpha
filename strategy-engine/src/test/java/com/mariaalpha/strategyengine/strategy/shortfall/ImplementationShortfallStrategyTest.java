package com.mariaalpha.strategyengine.strategy.shortfall;

import static com.mariaalpha.strategyengine.strategy.shortfall.ImplementationShortfallStrategy.MARKET_ZONE;
import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImplementationShortfallStrategyTest {

  private static final String AAPL = "AAPL";

  // Standard test window: 09:30-13:30, four equal one-hour slices.
  //   slice 0: 09:30-10:30, slice 1: 10:30-11:30, slice 2: 11:30-12:30, slice 3: 12:30-13:30
  private static final LocalTime WINDOW_START = LocalTime.of(9, 30);
  private static final LocalTime WINDOW_END = LocalTime.of(13, 30);
  private static final int FOUR_SLICES = 4;
  private static final double URGENCY = 0.5;

  private ImplementationShortfallStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new ImplementationShortfallStrategy();
  }

  @Test
  void nameReturnsIs() {
    assertThat(strategy.name()).isEqualTo("IS");
  }

  @Test
  void defaultScheduleSpansTwelveSlicesWithDefaultUrgency() {
    // Constructor builds the default 09:30-16:00 schedule with 12 slices and urgency 0.5.
    var params = strategy.getParameters();
    assertThat(params.get("totalSlices")).isEqualTo(12);
    assertThat(params.get("numSlices")).isEqualTo(12);
    assertThat(params.get("urgency")).isEqualTo(0.5);
  }

  @Test
  void evaluateReturnsEmptyBeforeConfiguration() {
    // Fresh strategy: schedule exists but targetQuantity is 0.
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateReturnsEmptyBeforeStartTime() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.onTick(quoteTick(AAPL, etInstant(9, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void firstSliceIsFrontLoaded() {
    // 1000 shares, 4 one-hour slices, urgency 0.5 → front-loaded [413, 263, 180, 144].
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.symbol()).isEqualTo(AAPL);
    assertThat(orderSignal.side()).isEqualTo(Side.BUY);
    assertThat(orderSignal.quantity()).isEqualTo(413);
    assertThat(orderSignal.orderType()).isEqualTo(OrderType.LIMIT);
    assertThat(orderSignal.limitPrice()).isEqualByComparingTo(new BigDecimal("178.54"));
    assertThat(orderSignal.strategyName()).isEqualTo("IS");
  }

  @Test
  void allocationsStrictlyDecreaseAndSumToTarget() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);

    var quantities = new ArrayList<Integer>();
    quantities.add(emitSlice(10)); // slice 0
    quantities.add(emitSlice(11)); // slice 1
    quantities.add(emitSlice(12)); // slice 2 (12:00 is inside slice 2: 11:30-12:30)
    quantities.add(emitSlice(13)); // slice 3 (13:00 is inside slice 3: 12:30-13:30)

    assertThat(quantities).containsExactly(413, 263, 180, 144);
    assertThat(quantities).isSortedAccordingTo((a, b) -> Integer.compare(b, a)); // strictly desc
    assertThat(quantities.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1000);
  }

  @Test
  void urgencyZeroDegradesToTwapEqualSlicing() {
    // urgency 0 → uniform 1/N allocation, identical to TWAP.
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, 0.0);

    var quantities = new ArrayList<Integer>();
    quantities.add(emitSlice(10));
    quantities.add(emitSlice(11));
    quantities.add(emitSlice(12));
    quantities.add(emitSlice(13));

    assertThat(quantities).containsExactly(250, 250, 250, 250);
  }

  @Test
  void negativeUrgencyAlsoDegradesToUniform() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, -1.0);
    assertThat(emitSlice(10)).isEqualTo(250);
  }

  @Test
  void higherUrgencyConcentratesMoreInFirstSlice() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, 0.5);
    int gentle = emitSlice(10);

    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, 2.0);
    int aggressive = emitSlice(10);

    assertThat(aggressive).isGreaterThan(gentle);
    assertThat(aggressive).isEqualTo(865); // [865, 117, 16, 2]
    assertThat(gentle).isEqualTo(413);
  }

  @Test
  void evaluateDoesNotReemitForSameSlice() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    strategy.evaluate(AAPL); // first call emits
    strategy.onTick(quoteTick(AAPL, etInstant(10, 15), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateEmitsForNextSliceWhenTimeAdvances() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);

    // Slice 0
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).get().extracting(OrderSignal::quantity).isEqualTo(413);

    // Slice 1 (10:30-11:30) — smaller, front-loaded allocation
    strategy.onTick(quoteTick(AAPL, etInstant(11, 0), "179.00", "179.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(263);
    assertThat(orderSignal.limitPrice()).isEqualByComparingTo(new BigDecimal("179.04"));
  }

  @Test
  void evaluateEmitsMarketSweepAtEndTime() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);

    // Execute only slice 0 (413 shares)
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    strategy.evaluate(AAPL);

    // Jump past end time
    strategy.onTick(quoteTick(AAPL, etInstant(13, 35), "180.00", "180.04"));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(587); // 1000 - 413
    assertThat(orderSignal.orderType()).isEqualTo(OrderType.MARKET);
    assertThat(orderSignal.limitPrice()).isNull();
  }

  @Test
  void sweepEmitsOnlyOnce() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.onTick(quoteTick(AAPL, etInstant(13, 35), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isPresent(); // sweep
    strategy.onTick(quoteTick(AAPL, etInstant(13, 40), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isEmpty(); // completed
  }

  @Test
  void noSweepWhenFullyExecuted() {
    // Single slice covering the whole window: it fully executes, so end-of-window has nothing left.
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, 1, URGENCY);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).get().extracting(OrderSignal::quantity).isEqualTo(1000);

    strategy.onTick(quoteTick(AAPL, etInstant(13, 35), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateIgnoresTicksForDifferentSymbol() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.52"));
    assertThat(strategy.evaluate("MSFT")).isEmpty();
  }

  @Test
  void tradePriceFallbackWhenNoQuote() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.52"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().limitPrice()).isEqualByComparingTo(new BigDecimal("178.52"));
  }

  @Test
  void sellSideUsesBidPrice() {
    configureStrategy(1000, Side.SELL, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.side()).isEqualTo(Side.SELL);
    assertThat(orderSignal.limitPrice()).isEqualByComparingTo(new BigDecimal("178.50"));
  }

  @Test
  void allocationRemainderGoesToLastSlice() {
    // 1001 shares over 3 one-hour slices (09:30-12:30), urgency 0.5 → 449, 308, 244 (sum 1001).
    configureStrategy(1001, Side.BUY, WINDOW_START, LocalTime.of(12, 30), 3, URGENCY);

    int total = 0;
    total += emitSlice(10);
    total += emitSlice(11);
    total += emitSlice(12);

    assertThat(total).isEqualTo(1001);
  }

  @Test
  void fullTradingDaySimulation() {
    // 10,000 shares, default 09:30-16:00 window, 12 thirty-two-and-a-half-minute slices.
    configureStrategy(10000, Side.BUY, LocalTime.of(9, 30), LocalTime.of(16, 0), 12, URGENCY);
    var signals = new ArrayList<OrderSignal>();

    var baseDate = LocalDate.of(2026, 3, 24);
    for (int hour = 9; hour <= 15; hour++) {
      int startMin = (hour == 9) ? 30 : 0;
      for (int min = startMin; min < 60; min += 15) {
        var ts = ZonedDateTime.of(baseDate, LocalTime.of(hour, min), MARKET_ZONE).toInstant();
        strategy.onTick(quoteTick(AAPL, ts, "178.50", "178.54"));
        strategy.evaluate(AAPL).ifPresent(signals::add);
      }
    }

    // EOD: tick at 16:00 triggers sweep if needed
    var closeTs = ZonedDateTime.of(baseDate, LocalTime.of(16, 0), MARKET_ZONE).toInstant();
    strategy.onTick(quoteTick(AAPL, closeTs, "178.50", "178.54"));
    strategy.evaluate(AAPL).ifPresent(signals::add);

    int totalQty = signals.stream().mapToInt(OrderSignal::quantity).sum();
    assertThat(totalQty).isEqualTo(10000);

    // All 12 slices fire as LIMIT orders; nothing left for the sweep.
    var limitSignals = signals.stream().filter(s -> s.orderType() == OrderType.LIMIT).toList();
    assertThat(limitSignals).hasSize(12);
    assertThat(signals).allMatch(s -> s.symbol().equals(AAPL));
    assertThat(signals).allMatch(s -> s.side() == Side.BUY);

    // Front-loading: the first slice's clip dwarfs the last.
    assertThat(limitSignals.getFirst().quantity()).isGreaterThan(limitSignals.getLast().quantity());
  }

  @Test
  void getParametersReturnsCurrentState() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    var params = strategy.getParameters();
    assertThat(params.get("targetQuantity")).isEqualTo(1000);
    assertThat(params.get("side")).isEqualTo("BUY");
    assertThat(params.get("numSlices")).isEqualTo(4);
    assertThat(params.get("urgency")).isEqualTo(0.5);
    assertThat(params.get("totalSlices")).isEqualTo(4);
    assertThat(params.get("executedSlices")).isEqualTo(0);

    @SuppressWarnings("unchecked")
    var slices = (List<ShortfallSlice>) params.get("slices");
    assertThat(slices).hasSize(4);
    assertThat(slices.getFirst().startTime()).isEqualTo(LocalTime.of(9, 30));
    assertThat(slices.getLast().endTime()).isEqualTo(LocalTime.of(13, 30));

    @SuppressWarnings("unchecked")
    var allocations = (List<Integer>) params.get("allocations");
    assertThat(allocations).containsExactly(413, 263, 180, 144);
  }

  @Test
  void executedSlicesCountTracksProgress() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    strategy.evaluate(AAPL);
    assertThat(strategy.getParameters().get("executedSlices")).isEqualTo(1);
  }

  @Test
  void updateParametersResetsState() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);

    // Execute slice 0
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isPresent();

    // Reconfigure with a new target and side
    configureStrategy(2000, Side.SELL, WINDOW_START, WINDOW_END, FOUR_SLICES, 0.0);

    // Slice 0 should fire again (state was reset) with the new (now uniform) allocation
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(500); // 2000 / 4 at urgency 0
    assertThat(orderSignal.side()).isEqualTo(Side.SELL);
  }

  @Test
  void partialUrgencyUpdatePreservesOtherFields() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    // Update only urgency to 0 (uniform); window, side, target, slice count must be retained.
    strategy.updateParameters(Map.of("urgency", 0.0));
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(250); // 1000 / 4 slices, now uniform
    assertThat(signal.get().side()).isEqualTo(Side.BUY);
  }

  @Test
  void invertedWindowProducesNoSchedule() {
    configureStrategy(
        1000, Side.BUY, LocalTime.of(13, 30), LocalTime.of(9, 30), FOUR_SLICES, URGENCY);
    assertThat(strategy.getParameters().get("totalSlices")).isEqualTo(0);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void zeroSlicesProducesNoSchedule() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, 0, URGENCY);
    assertThat(strategy.getParameters().get("totalSlices")).isEqualTo(0);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  /** Feeds a quote tick at {@code hour}:00 ET and returns the emitted slice quantity. */
  private int emitSlice(int hour) {
    strategy.onTick(quoteTick(AAPL, etInstant(hour, 0), "178.50", "178.54"));
    return strategy.evaluate(AAPL).orElseThrow().quantity();
  }

  private static Instant etInstant(int hour, int minute) {
    return ZonedDateTime.of(LocalDate.of(2026, 3, 24), LocalTime.of(hour, minute), MARKET_ZONE)
        .toInstant();
  }

  private static MarketTick quoteTick(String symbol, Instant ts, String bid, String ask) {
    return new MarketTick(
        symbol,
        ts,
        EventType.QUOTE,
        BigDecimal.ZERO,
        0L,
        new BigDecimal(bid),
        new BigDecimal(ask),
        100L,
        80L,
        0L,
        DataSource.ALPACA,
        false);
  }

  private static MarketTick tradeTick(String symbol, Instant ts, String price) {
    return new MarketTick(
        symbol,
        ts,
        EventType.TRADE,
        new BigDecimal(price),
        100L,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0L,
        0L,
        0L,
        DataSource.ALPACA,
        false);
  }

  private void configureStrategy(
      int targetQty, Side side, LocalTime start, LocalTime end, int numSlices, double urgency) {
    var params = new HashMap<String, Object>();
    params.put("targetQuantity", targetQty);
    params.put("side", side.name());
    params.put("startTime", start.toString());
    params.put("endTime", end.toString());
    params.put("numSlices", numSlices);
    params.put("urgency", urgency);
    strategy.updateParameters(params);
  }
}
