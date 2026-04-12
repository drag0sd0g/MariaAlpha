package com.mariaalpha.strategyengine.strategy.vwap;

import static com.mariaalpha.strategyengine.strategy.vwap.VwapStrategy.MARKET_ZONE;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VwapStrategyTest {

  public static final String AAPL = "AAPL";
  private VwapStrategy strategy;

  // Standard 3-bin profile for most tests:
  //   09:30-10:30 (40%), 10:30-11:30 (35%), 11:30-12:30 (25%)
  private static final List<TimeBin> THREE_BIN_PROFILE =
      List.of(
          new TimeBin(LocalTime.of(9, 30), LocalTime.of(10, 30), 0.40),
          new TimeBin(LocalTime.of(10, 30), LocalTime.of(11, 30), 0.35),
          new TimeBin(LocalTime.of(11, 30), LocalTime.of(12, 30), 0.25));

  @BeforeEach
  void setUp() {
    strategy = new VwapStrategy();
  }

  @Test
  void fullTradingDaySimulation() {
    var volumeProfile = buildFullDayProfile();
    var targetQty = 10000;
    configureStrategy(targetQty, Side.BUY, volumeProfile);
    var signals = new ArrayList<OrderSignal>();

    // Simulate ticks every 15 minutes from 09:30 to 16:00
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

    // Verify all bins emitted exactly once (13 bin signals + possibly 1 sweep)
    int totalQty = signals.stream().mapToInt(OrderSignal::quantity).sum();
    assertThat(totalQty).isEqualTo(targetQty);

    // All non-sweep signals should be LIMIT orders
    var limitSignals = signals.stream().filter(s -> s.orderType() == OrderType.LIMIT).toList();
    assertThat(limitSignals).hasSize(13);

    // Every signal should be for APPL
    assertThat(signals).allMatch(s -> s.symbol().equals(AAPL));
    assertThat(signals).allMatch(s -> s.side() == Side.BUY);
  }

  @Test
  void allocationRoundingSumsCorrectly() {
    var profile =
        List.of(
            new TimeBin(LocalTime.of(9, 30), LocalTime.of(10, 30), 0.333),
            new TimeBin(LocalTime.of(10, 30), LocalTime.of(11, 30), 0.333),
            new TimeBin(LocalTime.of(11, 30), LocalTime.of(12, 30), 0.334));
    configureStrategy(1001, Side.BUY, profile);

    // Execute all bins and collect quantities
    int total = 0;
    strategy.onTick(quoteTick(AAPL, etInstant(9, 45), "178.50", "178.54"));
    total += strategy.evaluate(AAPL).get().quantity();
    strategy.onTick(quoteTick(AAPL, etInstant(10, 45), "178.50", "178.54"));
    total += strategy.evaluate(AAPL).get().quantity();
    strategy.onTick(quoteTick(AAPL, etInstant(11, 45), "178.50", "178.54"));
    total += strategy.evaluate(AAPL).get().quantity();

    assertThat(total).isEqualTo(1001);
  }

  @Test
  void nameReturnsVwap() {
    assertThat(strategy.name()).isEqualTo("VWAP");
  }

  @Test
  void evaluateReturnsEmptyBeforeConfiguration() {
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateReturnsEmptyBeforeStartTime() {
    configureStrategy(1000, Side.BUY, THREE_BIN_PROFILE);
    strategy.onTick(quoteTick(AAPL, etInstant(9, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateEmitsOrderInCorrectBin() {
    configureStrategy(1000, Side.BUY, THREE_BIN_PROFILE);
    strategy.onTick(quoteTick(AAPL, etInstant(9, 45), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.symbol()).isEqualTo(AAPL);
    assertThat(orderSignal.side()).isEqualTo(Side.BUY);
    assertThat(orderSignal.quantity()).isEqualTo(400);
    assertThat(orderSignal.orderType()).isEqualTo(OrderType.LIMIT);
    assertThat(orderSignal.limitPrice()).isEqualByComparingTo(new BigDecimal("178.54"));
    assertThat(orderSignal.strategyName()).isEqualTo("VWAP");
  }

  @Test
  void evaluateDoesNotReemitForSameBin() {
    configureStrategy(1000, Side.BUY, THREE_BIN_PROFILE);
    strategy.onTick(quoteTick(AAPL, etInstant(10, 45), "178.50", "178.54"));
    strategy.evaluate(AAPL); // first call emits
    strategy.onTick(quoteTick(AAPL, etInstant(10, 45), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateEmitsForNextBinWhenTimeAdvances() {
    configureStrategy(1000, Side.BUY, THREE_BIN_PROFILE);

    // Bin 0
    strategy.onTick(quoteTick(AAPL, etInstant(9, 45), "178.50", "178.54"));
    strategy.evaluate(AAPL);

    // Bin 1
    strategy.onTick(quoteTick(AAPL, etInstant(10, 45), "179.00", "179.04"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(350);
    assertThat(orderSignal.limitPrice()).isEqualByComparingTo(new BigDecimal("179.04"));
  }

  @Test
  void evaluateEmitsMarketSweepAtEndTime() {
    configureStrategy(1000, Side.BUY, THREE_BIN_PROFILE);

    // Execute only bin 0 (400 shares)
    strategy.onTick(quoteTick(AAPL, etInstant(9, 45), "178.50", "178.54"));
    strategy.evaluate(AAPL);

    // Jump past end time
    strategy.onTick(quoteTick(AAPL, etInstant(12, 35), "180.00", "180.04"));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(600);
    assertThat(orderSignal.orderType()).isEqualTo(OrderType.MARKET);
    assertThat(orderSignal.limitPrice()).isNull();
  }

  @Test
  void evaluateIgnoresTicksForDifferentSymbol() {
    configureStrategy(1000, Side.BUY, THREE_BIN_PROFILE);
    strategy.onTick(tradeTick(AAPL, etInstant(9, 45), "178.52"));
    assertThat(strategy.evaluate("MSFT")).isEmpty();
  }

  @Test
  void tradePriceFallbackWhenNoQuote() {
    configureStrategy(1000, Side.BUY, THREE_BIN_PROFILE);
    strategy.onTick(tradeTick(AAPL, etInstant(9, 45), "178.52"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.limitPrice()).isEqualTo(new BigDecimal("178.52"));
  }

  @Test
  void getParametersReturnsCurrentState() {
    configureStrategy(1000, Side.BUY, THREE_BIN_PROFILE);
    var params = strategy.getParameters();
    assertThat(params.get("targetQuantity")).isEqualTo(1000);
    assertThat(params.get("side")).isEqualTo("BUY");
    assertThat(params.get("totalBins")).isEqualTo(3);
    assertThat(params.get("executedBins")).isEqualTo(0);
  }

  @Test
  void updateParametersResetsState() {
    configureStrategy(1000, Side.BUY, THREE_BIN_PROFILE);

    // Execute bin 0
    strategy.onTick(quoteTick("AAPL", etInstant(9, 45), "178.50", "178.54"));
    assertThat(strategy.evaluate("AAPL")).isPresent();

    // Reconfigure with new target
    configureStrategy(2000, Side.SELL, THREE_BIN_PROFILE);

    // Bin 0 should fire again (state was reset)
    strategy.onTick(quoteTick("AAPL", etInstant(9, 45), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(800);
    assertThat(orderSignal.side()).isEqualTo(Side.SELL);
  }

  @Test
  void sellSideUsesBidPrice() {
    configureStrategy(1000, Side.SELL, THREE_BIN_PROFILE);
    strategy.onTick(quoteTick("AAPL", etInstant(9, 45), "178.50", "178.54"));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.quantity()).isEqualTo(400);
    assertThat(orderSignal.side()).isEqualTo(Side.SELL);
    assertThat(signal.get().limitPrice()).isEqualByComparingTo(new BigDecimal("178.50"));
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

  private static List<TimeBin> buildFullDayProfile() {
    // 13 bins, 30 min each, from 09:30 to 16:00
    // U-shaped volume distribution
    double[] fractions = {
      0.12, 0.09, 0.07, 0.06, 0.05, 0.05, 0.05, 0.05, 0.06, 0.07, 0.09, 0.11, 0.13
    };
    var bins = new ArrayList<TimeBin>();
    var time = LocalTime.of(9, 30);
    for (double fraction : fractions) {
      var end = time.plusMinutes(30);
      bins.add(new TimeBin(time, end, fraction));
      time = end;
    }
    return bins;
  }

  private void configureStrategy(int targetQty, Side side, List<TimeBin> volumeProfile) {
    strategy.updateParameters(
        Map.of(
            "targetQuantity",
            targetQty,
            "side",
            side.name(),
            "startTime",
            volumeProfile.getFirst().startTime().toString(),
            "endTime",
            volumeProfile.getLast().endTime().toString(),
            "volumeProfile",
            volumeProfile));
  }
}
