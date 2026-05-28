package com.mariaalpha.strategyengine.strategy.close;

import static com.mariaalpha.strategyengine.strategy.close.CloseStrategy.MARKET_ZONE;
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

class CloseStrategyTest {

  private static final String AAPL = "AAPL";
  // Standard test window: 15:00-16:00 with MOC offset 10 min → mocCutoff = 15:50, pre-close
  // window = 15:00-15:50 (50 minutes), 5 ten-minute slices.
  private static final LocalTime WINDOW_START = LocalTime.of(15, 0);
  private static final LocalTime CLOSE_TIME = LocalTime.of(16, 0);
  private static final int MOC_OFFSET_MIN = 10;
  private static final int FIVE_SLICES = 5;
  private static final double HALF = 0.50;

  private CloseStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new CloseStrategy();
  }

  @Test
  void nameReturnsClose() {
    assertThat(strategy.name()).isEqualTo("CLOSE");
  }

  @Test
  void defaultParametersHaveSensibleValues() {
    var params = strategy.getParameters();
    assertThat(params.get("windowStart")).isEqualTo("15:30");
    assertThat(params.get("closeTime")).isEqualTo("16:00");
    assertThat(params.get("mocOffsetMinutes")).isEqualTo(5);
    assertThat(params.get("preCloseFraction")).isEqualTo(0.30);
    assertThat(params.get("numPreCloseSlices")).isEqualTo(6);
    assertThat(params.get("targetQuantity")).isEqualTo(0);
    assertThat(params.get("mocFired")).isEqualTo(false);
  }

  @Test
  void evaluateReturnsEmptyBeforeConfiguration() {
    // Before target is set, evaluate is empty regardless of incoming ticks.
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateReturnsEmptyBeforeWindowStart() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(14, 30), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void preCloseSliceEmitsLimitOrderAtBidAsk() {
    // 1000 shares, 50% preCloseFraction → 500 in pre-close (5 slices × 100), 500 in MOC.
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.symbol()).isEqualTo(AAPL);
    assertThat(orderSignal.side()).isEqualTo(Side.BUY);
    assertThat(orderSignal.quantity()).isEqualTo(100);
    assertThat(orderSignal.orderType()).isEqualTo(OrderType.LIMIT);
    assertThat(orderSignal.limitPrice()).isEqualByComparingTo(new BigDecimal("178.54"));
    assertThat(orderSignal.strategyName()).isEqualTo("CLOSE");
  }

  @Test
  void evaluateDoesNotReemitForSameSlice() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    strategy.evaluate(AAPL); // fires slice 0
    strategy.onTick(quoteTick(AAPL, etInstant(15, 9), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void successiveSlicesFireAsClockAdvances() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    var quantities = new ArrayList<Integer>();
    quantities.add(emitSliceAt(15, 5));
    quantities.add(emitSliceAt(15, 15));
    quantities.add(emitSliceAt(15, 25));
    quantities.add(emitSliceAt(15, 35));
    quantities.add(emitSliceAt(15, 45));
    // All five 10-min slices fire 100 shares each → 500 in pre-close (the 50% half).
    assertThat(quantities).containsExactly(100, 100, 100, 100, 100);
  }

  @Test
  void mocFiresAtCutoffSweepingResidualAndPlannedMoc() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);

    // Execute first two pre-close slices (100 + 100 = 200).
    emitSliceAt(15, 5);
    emitSliceAt(15, 15);

    // Jump to MOC cutoff (15:50). Remaining = 1000 - 200 = 800 — the 500 planned MOC + the 300
    // skipped slice quantity, swept in a single MARKET order.
    strategy.onTick(quoteTick(AAPL, etInstant(15, 50), "180.00", "180.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(800);
    assertThat(orderSignal.orderType()).isEqualTo(OrderType.MARKET);
    assertThat(orderSignal.limitPrice()).isNull();
    assertThat(orderSignal.strategyName()).isEqualTo("CLOSE");
  }

  @Test
  void mocFiresOnlyOnce() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 55), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isPresent(); // MOC fires
    strategy.onTick(quoteTick(AAPL, etInstant(15, 58), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
    assertThat(strategy.getParameters().get("mocFired")).isEqualTo(true);
  }

  @Test
  void pureMocWhenPreCloseFractionIsZero() {
    // 0% pre-close → all 1000 shares go in the MOC, pre-close slices fire 0 shares.
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, 0.0, FIVE_SLICES);
    var params = strategy.getParameters();
    assertThat(params.get("mocAllocation")).isEqualTo(1000);

    @SuppressWarnings("unchecked")
    var allocations = (List<Integer>) params.get("sliceAllocations");
    assertThat(allocations).containsExactly(0, 0, 0, 0, 0);

    // Pre-close ticks emit no LIMIT signals.
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();

    // At MOC cutoff, the full 1000 fires as MARKET.
    strategy.onTick(quoteTick(AAPL, etInstant(15, 50), "180.00", "180.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1000);
    assertThat(signal.get().orderType()).isEqualTo(OrderType.MARKET);
  }

  @Test
  void pureWorkingWhenPreCloseFractionIsOne() {
    // 100% pre-close → all 1000 shares spread across the 5 slices, MOC fires for 0 shares.
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, 1.0, FIVE_SLICES);

    var quantities = new ArrayList<Integer>();
    quantities.add(emitSliceAt(15, 5));
    quantities.add(emitSliceAt(15, 15));
    quantities.add(emitSliceAt(15, 25));
    quantities.add(emitSliceAt(15, 35));
    quantities.add(emitSliceAt(15, 45));
    // 1000 / 5 = 200 per slice; last absorbs remainder = 1000 - 4*200 = 200.
    assertThat(quantities).containsExactly(200, 200, 200, 200, 200);
    assertThat(quantities.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1000);

    // At MOC cutoff, remaining = 0 → no MARKET emit, but mocFired flips to true.
    strategy.onTick(quoteTick(AAPL, etInstant(15, 55), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
    assertThat(strategy.getParameters().get("mocFired")).isEqualTo(true);
  }

  @Test
  void allocationsSumExactlyToTarget() {
    // 1001 shares, 50%, 5 slices → preCloseTotal = 501. perSlice = round(501/5) = 100, last
    // absorbs remainder = 501 - 4×100 = 101. mocAllocation = 1001 - 501 = 500.
    configure(1001, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    var params = strategy.getParameters();

    @SuppressWarnings("unchecked")
    var allocations = (List<Integer>) params.get("sliceAllocations");
    int mocAlloc = (int) params.get("mocAllocation");
    assertThat(allocations.stream().mapToInt(Integer::intValue).sum() + mocAlloc).isEqualTo(1001);
  }

  @Test
  void preCloseFractionAboveOneIsClamped() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, 1.5, FIVE_SLICES);
    assertThat(strategy.getParameters().get("mocAllocation")).isEqualTo(0);
  }

  @Test
  void preCloseFractionBelowZeroIsClamped() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, -0.5, FIVE_SLICES);
    assertThat(strategy.getParameters().get("mocAllocation")).isEqualTo(1000);
  }

  @Test
  void sellSideUsesBidPrice() {
    configure(1000, Side.SELL, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().side()).isEqualTo(Side.SELL);
    assertThat(signal.get().limitPrice()).isEqualByComparingTo(new BigDecimal("178.50"));
  }

  @Test
  void tradePriceFallbackWhenNoQuote() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(tradeTick(AAPL, etInstant(15, 5), "178.52"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().limitPrice()).isEqualByComparingTo(new BigDecimal("178.52"));
  }

  @Test
  void evaluateIgnoresTicksForDifferentSymbol() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    assertThat(strategy.evaluate("MSFT")).isEmpty();
  }

  @Test
  void lateBindingAfterCutoffStillFiresMoc() {
    // Strategy bound at 15:58, between mocCutoff (15:50) and closeTime (16:00).
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 58), "180.00", "180.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1000);
    assertThat(signal.get().orderType()).isEqualTo(OrderType.MARKET);
  }

  @Test
  void postCloseSweepEmitsResidualAsMarket() {
    // Strategy bound *after* closeTime — no MOC opportunity, sweep instead.
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(16, 5), "180.00", "180.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1000);
    assertThat(signal.get().orderType()).isEqualTo(OrderType.MARKET);

    // Subsequent post-close ticks are quiet — completed flag prevents re-fire.
    strategy.onTick(quoteTick(AAPL, etInstant(16, 10), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void degenerateScheduleWhenWindowInverted() {
    configure(1000, Side.BUY, LocalTime.of(16, 0), CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    var params = strategy.getParameters();
    assertThat(params.get("totalPreCloseSlices")).isEqualTo(0);
    assertThat(params.get("mocAllocation")).isEqualTo(1000);
  }

  @Test
  void degenerateScheduleWhenMocOffsetOvershootsWindow() {
    // mocOffset (90 min) is bigger than the 60-min window → mocCutoff clamped to windowStart,
    // pre-close window is empty, everything flows through the MOC.
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, 90, HALF, FIVE_SLICES);
    var params = strategy.getParameters();
    assertThat(params.get("totalPreCloseSlices")).isEqualTo(0);
    assertThat(params.get("mocAllocation")).isEqualTo(1000);
  }

  @Test
  void zeroSlicesProducesNoPreCloseSchedule() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, 0);
    var params = strategy.getParameters();
    assertThat(params.get("totalPreCloseSlices")).isEqualTo(0);
    assertThat(params.get("mocAllocation")).isEqualTo(1000);

    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty(); // pre-close inactive

    strategy.onTick(quoteTick(AAPL, etInstant(15, 50), "180.00", "180.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1000);
    assertThat(signal.get().orderType()).isEqualTo(OrderType.MARKET);
  }

  @Test
  void getParametersExposesSchedule() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    var params = strategy.getParameters();

    @SuppressWarnings("unchecked")
    var slices = (List<CloseSlice>) params.get("preCloseSlices");
    assertThat(slices).hasSize(5);
    assertThat(slices.getFirst().startTime()).isEqualTo(LocalTime.of(15, 0));
    assertThat(slices.getLast().endTime()).isEqualTo(LocalTime.of(15, 50));

    @SuppressWarnings("unchecked")
    var allocations = (List<Integer>) params.get("sliceAllocations");
    assertThat(allocations).hasSize(5);
    assertThat(allocations.stream().mapToInt(Integer::intValue).sum()).isEqualTo(500);
    assertThat(params.get("mocAllocation")).isEqualTo(500);
  }

  @Test
  void partialParameterUpdatePreservesOtherFields() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.updateParameters(Map.of("preCloseFraction", 0.0));
    // Window/side/target/slice count must remain — only the split changes.
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    // preCloseFraction=0 → each pre-close slice allocates 0 shares.
    assertThat(strategy.evaluate(AAPL)).isEmpty();

    strategy.onTick(quoteTick(AAPL, etInstant(15, 50), "180.00", "180.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1000); // full size in MOC
  }

  @Test
  void updateParametersResetsExecutionState() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isPresent(); // slice 0 fires

    // Reconfigure with new target — slice 0 should be re-eligible.
    configure(2000, Side.SELL, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(200); // 50% of 2000 / 5 slices
    assertThat(signal.get().side()).isEqualTo(Side.SELL);
  }

  @Test
  void fullSessionSimulationCompletesByTheClose() {
    // 10,000-share parent, default window 15:30-16:00 with MOC offset 5 → mocCutoff 15:55,
    // 6 pre-close slices of ~4 min 10 s each, 30% pre-close fraction.
    var params = new HashMap<String, Object>();
    params.put("targetQuantity", 10000);
    params.put("side", "BUY");
    strategy.updateParameters(params);

    var signals = new ArrayList<OrderSignal>();
    var baseDate = LocalDate.of(2026, 3, 24);
    for (int min = 30; min < 60; min++) {
      var ts = ZonedDateTime.of(baseDate, LocalTime.of(15, min), MARKET_ZONE).toInstant();
      strategy.onTick(quoteTick(AAPL, ts, "178.50", "178.54"));
      strategy.evaluate(AAPL).ifPresent(signals::add);
    }
    // EOD: tick at 16:00 confirms completion (no further emits expected).
    var closeTs = ZonedDateTime.of(baseDate, LocalTime.of(16, 0), MARKET_ZONE).toInstant();
    strategy.onTick(quoteTick(AAPL, closeTs, "178.50", "178.54"));
    strategy.evaluate(AAPL).ifPresent(signals::add);

    int totalQty = signals.stream().mapToInt(OrderSignal::quantity).sum();
    assertThat(totalQty).isEqualTo(10000);

    var marketSignals = signals.stream().filter(s -> s.orderType() == OrderType.MARKET).toList();
    var limitSignals = signals.stream().filter(s -> s.orderType() == OrderType.LIMIT).toList();
    assertThat(marketSignals).hasSize(1); // exactly one MOC
    assertThat(marketSignals.getFirst().quantity()).isEqualTo(7000); // 70% in MOC
    assertThat(limitSignals.stream().mapToInt(OrderSignal::quantity).sum()).isEqualTo(3000);
  }

  /**
   * Feeds a quote tick at {@code hour}:{@code minute} ET and returns the emitted slice quantity.
   */
  private int emitSliceAt(int hour, int minute) {
    strategy.onTick(quoteTick(AAPL, etInstant(hour, minute), "178.50", "178.54"));
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

  private void configure(
      int targetQty,
      Side side,
      LocalTime start,
      LocalTime close,
      int mocOffsetMin,
      double preCloseFraction,
      int numSlices) {
    var params = new HashMap<String, Object>();
    params.put("targetQuantity", targetQty);
    params.put("side", side.name());
    params.put("windowStart", start.toString());
    params.put("closeTime", close.toString());
    params.put("mocOffsetMinutes", mocOffsetMin);
    params.put("preCloseFraction", preCloseFraction);
    params.put("numPreCloseSlices", numSlices);
    strategy.updateParameters(params);
  }
}
