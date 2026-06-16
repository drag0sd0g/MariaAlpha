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
    var params = strategy.getParameters();
    assertThat(params.get("totalSlices")).isEqualTo(12);
    assertThat(params.get("numSlices")).isEqualTo(12);
    assertThat(params.get("urgency")).isEqualTo(0.5);
  }

  @Test
  void evaluateReturnsEmptyBeforeConfiguration() {
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
    quantities.add(emitSlice(10));
    quantities.add(emitSlice(11));
    quantities.add(emitSlice(12));
    quantities.add(emitSlice(13));

    assertThat(quantities).containsExactly(413, 263, 180, 144);
    assertThat(quantities).isSortedAccordingTo((a, b) -> Integer.compare(b, a));
    assertThat(quantities.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1000);
  }

  @Test
  void urgencyZeroDegradesToTwapEqualSlicing() {
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
    assertThat(aggressive).isEqualTo(865);
    assertThat(gentle).isEqualTo(413);
  }

  @Test
  void evaluateDoesNotReemitForSameSlice() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    strategy.evaluate(AAPL);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 15), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateEmitsForNextSliceWhenTimeAdvances() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);

    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).get().extracting(OrderSignal::quantity).isEqualTo(413);

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

    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    strategy.evaluate(AAPL);

    strategy.onTick(quoteTick(AAPL, etInstant(13, 35), "180.00", "180.04"));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(587);
    assertThat(orderSignal.orderType()).isEqualTo(OrderType.MARKET);
    assertThat(orderSignal.limitPrice()).isNull();
  }

  @Test
  void sweepEmitsOnlyOnce() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.onTick(quoteTick(AAPL, etInstant(13, 35), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isPresent();
    strategy.onTick(quoteTick(AAPL, etInstant(13, 40), "180.00", "180.04"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void noSweepWhenFullyExecuted() {
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
    configureStrategy(1001, Side.BUY, WINDOW_START, LocalTime.of(12, 30), 3, URGENCY);

    int total = 0;
    total += emitSlice(10);
    total += emitSlice(11);
    total += emitSlice(12);

    assertThat(total).isEqualTo(1001);
  }

  @Test
  void fullTradingDaySimulation() {
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

    var closeTs = ZonedDateTime.of(baseDate, LocalTime.of(16, 0), MARKET_ZONE).toInstant();
    strategy.onTick(quoteTick(AAPL, closeTs, "178.50", "178.54"));
    strategy.evaluate(AAPL).ifPresent(signals::add);

    int totalQty = signals.stream().mapToInt(OrderSignal::quantity).sum();
    assertThat(totalQty).isEqualTo(10000);

    var limitSignals = signals.stream().filter(s -> s.orderType() == OrderType.LIMIT).toList();
    assertThat(limitSignals).hasSize(12);
    assertThat(signals).allMatch(s -> s.symbol().equals(AAPL));
    assertThat(signals).allMatch(s -> s.side() == Side.BUY);

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

    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isPresent();

    configureStrategy(2000, Side.SELL, WINDOW_START, WINDOW_END, FOUR_SLICES, 0.0);

    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(500);
    assertThat(orderSignal.side()).isEqualTo(Side.SELL);
  }

  @Test
  void partialUrgencyUpdatePreservesOtherFields() {
    configureStrategy(1000, Side.BUY, WINDOW_START, WINDOW_END, FOUR_SLICES, URGENCY);
    strategy.updateParameters(Map.of("urgency", 0.0));
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(250);
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
