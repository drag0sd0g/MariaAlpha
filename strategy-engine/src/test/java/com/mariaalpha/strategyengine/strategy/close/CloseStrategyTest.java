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
    strategy.evaluate(AAPL);
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
    assertThat(quantities).containsExactly(100, 100, 100, 100, 100);
  }

  @Test
  void mocFiresAtCutoffSweepingResidualAndPlannedMoc() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);

    emitSliceAt(15, 5);
    emitSliceAt(15, 15);

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
    assertThat(strategy.evaluate(AAPL)).isPresent();
    strategy.onTick(quoteTick(AAPL, etInstant(15, 58), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
    assertThat(strategy.getParameters().get("mocFired")).isEqualTo(true);
  }

  @Test
  void pureMocWhenPreCloseFractionIsZero() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, 0.0, FIVE_SLICES);
    var params = strategy.getParameters();
    assertThat(params.get("mocAllocation")).isEqualTo(1000);

    @SuppressWarnings("unchecked")
    var allocations = (List<Integer>) params.get("sliceAllocations");
    assertThat(allocations).containsExactly(0, 0, 0, 0, 0);

    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();

    strategy.onTick(quoteTick(AAPL, etInstant(15, 50), "180.00", "180.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1000);
    assertThat(signal.get().orderType()).isEqualTo(OrderType.MARKET);
  }

  @Test
  void pureWorkingWhenPreCloseFractionIsOne() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, 1.0, FIVE_SLICES);

    var quantities = new ArrayList<Integer>();
    quantities.add(emitSliceAt(15, 5));
    quantities.add(emitSliceAt(15, 15));
    quantities.add(emitSliceAt(15, 25));
    quantities.add(emitSliceAt(15, 35));
    quantities.add(emitSliceAt(15, 45));
    assertThat(quantities).containsExactly(200, 200, 200, 200, 200);
    assertThat(quantities.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1000);

    strategy.onTick(quoteTick(AAPL, etInstant(15, 55), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
    assertThat(strategy.getParameters().get("mocFired")).isEqualTo(true);
  }

  @Test
  void allocationsSumExactlyToTarget() {
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
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 58), "180.00", "180.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1000);
    assertThat(signal.get().orderType()).isEqualTo(OrderType.MARKET);
  }

  @Test
  void postCloseSweepEmitsResidualAsMarket() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(16, 5), "180.00", "180.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1000);
    assertThat(signal.get().orderType()).isEqualTo(OrderType.MARKET);

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
    assertThat(strategy.evaluate(AAPL)).isEmpty();

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
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();

    strategy.onTick(quoteTick(AAPL, etInstant(15, 50), "180.00", "180.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1000);
  }

  @Test
  void updateParametersResetsExecutionState() {
    configure(1000, Side.BUY, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isPresent();

    configure(2000, Side.SELL, WINDOW_START, CLOSE_TIME, MOC_OFFSET_MIN, HALF, FIVE_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(15, 5), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(200);
    assertThat(signal.get().side()).isEqualTo(Side.SELL);
  }

  @Test
  void fullSessionSimulationCompletesByTheClose() {
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
    var closeTs = ZonedDateTime.of(baseDate, LocalTime.of(16, 0), MARKET_ZONE).toInstant();
    strategy.onTick(quoteTick(AAPL, closeTs, "178.50", "178.54"));
    strategy.evaluate(AAPL).ifPresent(signals::add);

    int totalQty = signals.stream().mapToInt(OrderSignal::quantity).sum();
    assertThat(totalQty).isEqualTo(10000);

    var marketSignals = signals.stream().filter(s -> s.orderType() == OrderType.MARKET).toList();
    var limitSignals = signals.stream().filter(s -> s.orderType() == OrderType.LIMIT).toList();
    assertThat(marketSignals).hasSize(1);
    assertThat(marketSignals.getFirst().quantity()).isEqualTo(7000);
    assertThat(limitSignals.stream().mapToInt(OrderSignal::quantity).sum()).isEqualTo(3000);
  }

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
