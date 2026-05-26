package com.mariaalpha.strategyengine.strategy.twap;

import static com.mariaalpha.strategyengine.strategy.twap.TwapStrategy.MARKET_ZONE;
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

class TwapStrategyTest {

  private static final String AAPL = "AAPL";

  // Standard test window: 09:30-13:30, four equal one-hour slices.
  //   slice 0: 09:30-10:30, slice 1: 10:30-11:30, slice 2: 11:30-12:30, slice 3: 12:30-13:30
  private static final LocalTime WINDOW_START = LocalTime.of(9, 30);
  private static final LocalTime WINDOW_END = LocalTime.of(13, 30);
  private static final int FOUR_SLICES = 4;

  private TwapStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new TwapStrategy();
  }

  @Test
  void nameReturnsTwap() {
    assertThat(strategy.name()).isEqualTo("TWAP");
  }

  @Test
  void defaultScheduleSpansTwelveSlices() {
    // Constructor builds the default 09:30-16:00 schedule with 12 slices.
    var params = strategy.getParameters();
    assertThat(params.get("totalSlices")).isEqualTo(12);
    assertThat(params.get("numSlices")).isEqualTo(12);
  }

  @Test
  void evaluateReturnsEmptyBeforeConfiguration() {
    // Fresh strategy: schedule exists but targetQuantity is 0.
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateReturnsEmptyBeforeStartTime() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(9, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateEmitsOrderInCorrectSlice() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.symbol()).isEqualTo(AAPL);
    assertThat(orderSignal.side()).isEqualTo(Side.BUY);
    assertThat(orderSignal.quantity()).isEqualTo(250);
    assertThat(orderSignal.orderType()).isEqualTo(OrderType.LIMIT);
    assertThat(orderSignal.limitPrice()).isEqualByComparingTo(new BigDecimal("178.54"));
    assertThat(orderSignal.strategyName()).isEqualTo("TWAP");
  }

  @Test
  void evaluateDoesNotReemitForSameSlice() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    strategy.evaluate(AAPL); // first call emits
    strategy.onTick(quoteTick(AAPL, etInstant(10, 15), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateEmitsForNextSliceWhenTimeAdvances() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);

    // Slice 0
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    strategy.evaluate(AAPL);

    // Slice 1 (10:30-11:30)
    strategy.onTick(quoteTick(AAPL, etInstant(11, 0), "179.00", "179.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(250);
    assertThat(orderSignal.limitPrice()).isEqualByComparingTo(new BigDecimal("179.04"));
  }

  @Test
  void evaluateEmitsMarketSweepAtEndTime() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);

    // Execute only slice 0 (250 shares)
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    strategy.evaluate(AAPL);

    // Jump past end time
    strategy.onTick(quoteTick(AAPL, etInstant(13, 35), "180.00", "180.04"));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(750);
    assertThat(orderSignal.orderType()).isEqualTo(OrderType.MARKET);
    assertThat(orderSignal.limitPrice()).isNull();
  }

  @Test
  void sweepEmitsOnlyOnce() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(13, 35), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isPresent(); // sweep
    strategy.onTick(quoteTick(AAPL, etInstant(13, 40), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isEmpty(); // completed
  }

  @Test
  void noSweepWhenFullyExecuted() {
    // Single slice covering the whole window: it fully executes, so end-of-window has nothing left.
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, 1);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).get().extracting(OrderSignal::quantity).isEqualTo(1000);

    strategy.onTick(quoteTick(AAPL, etInstant(13, 35), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateIgnoresTicksForDifferentSymbol() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.52"));
    assertThat(strategy.evaluate("MSFT")).isEmpty();
  }

  @Test
  void tradePriceFallbackWhenNoQuote() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.52"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().limitPrice()).isEqualByComparingTo(new BigDecimal("178.52"));
  }

  @Test
  void sellSideUsesBidPrice() {
    configureStrategy(1000, Side.SELL, WINDOW_START, WINDOW_END, FOUR_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.side()).isEqualTo(Side.SELL);
    assertThat(orderSignal.limitPrice()).isEqualByComparingTo(new BigDecimal("178.50"));
  }

  @Test
  void allocationRemainderGoesToLastSlice() {
    // 1001 shares over 3 one-hour slices (09:30-12:30): 334, 334, 333.
    configureStrategy(1001, Side.BUY, WINDOW_START, LocalTime.of(12, 30), 3);

    int total = 0;
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    total += strategy.evaluate(AAPL).orElseThrow().quantity();
    strategy.onTick(quoteTick(AAPL, etInstant(11, 0), "178.50", "178.54"));
    total += strategy.evaluate(AAPL).orElseThrow().quantity();
    strategy.onTick(quoteTick(AAPL, etInstant(12, 0), "178.50", "178.54"));
    total += strategy.evaluate(AAPL).orElseThrow().quantity();

    assertThat(total).isEqualTo(1001);
  }

  @Test
  void fullTradingDaySimulation() {
    // 10,000 shares, default 09:30-16:00 window, 13 thirty-minute slices.
    configureStrategy(10000, Side.BUY, LocalTime.of(9, 30), LocalTime.of(16, 0), 13);
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

    // All 13 slices fire as LIMIT orders; nothing left for the sweep.
    var limitSignals = signals.stream().filter(s -> s.orderType() == OrderType.LIMIT).toList();
    assertThat(limitSignals).hasSize(13);
    assertThat(signals).allMatch(s -> s.symbol().equals(AAPL));
    assertThat(signals).allMatch(s -> s.side() == Side.BUY);
  }

  @Test
  void getParametersReturnsCurrentState() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);
    var params = strategy.getParameters();
    assertThat(params.get("targetQuantity")).isEqualTo(1000);
    assertThat(params.get("side")).isEqualTo("BUY");
    assertThat(params.get("numSlices")).isEqualTo(4);
    assertThat(params.get("totalSlices")).isEqualTo(4);
    assertThat(params.get("executedSlices")).isEqualTo(0);

    @SuppressWarnings("unchecked")
    var slices = (List<TwapSlice>) params.get("slices");
    assertThat(slices).hasSize(4);
    assertThat(slices.getFirst().startTime()).isEqualTo(LocalTime.of(9, 30));
    assertThat(slices.getLast().endTime()).isEqualTo(LocalTime.of(13, 30));
  }

  @Test
  void executedSlicesCountTracksProgress() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    strategy.evaluate(AAPL);
    assertThat(strategy.getParameters().get("executedSlices")).isEqualTo(1);
  }

  @Test
  void updateParametersResetsState() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);

    // Execute slice 0
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isPresent();

    // Reconfigure with a new target and side
    configureStrategy(2000, Side.SELL, WINDOW_START, WINDOW_END, FOUR_SLICES);

    // Slice 0 should fire again (state was reset) with the new allocation
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(500);
    assertThat(orderSignal.side()).isEqualTo(Side.SELL);
  }

  @Test
  void partialParameterUpdatePreservesOtherFields() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES);
    // Update only the target quantity; window and slice count must be retained.
    strategy.updateParameters(Map.of("targetQuantity", 800));
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(200); // 800 / 4 slices
  }

  @Test
  void invertedWindowProducesNoSchedule() {
    configureStrategy(1000, Side.BUY, LocalTime.of(13, 30), LocalTime.of(9, 30), FOUR_SLICES);
    assertThat(strategy.getParameters().get("totalSlices")).isEqualTo(0);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void zeroSlicesProducesNoSchedule() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, 0);
    assertThat(strategy.getParameters().get("totalSlices")).isEqualTo(0);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
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
      int targetQty, Side side, LocalTime start, LocalTime end, int numSlices) {
    var params = new HashMap<String, Object>();
    params.put("targetQuantity", targetQty);
    params.put("side", side.name());
    params.put("startTime", start.toString());
    params.put("endTime", end.toString());
    params.put("numSlices", numSlices);
    strategy.updateParameters(params);
  }
}
