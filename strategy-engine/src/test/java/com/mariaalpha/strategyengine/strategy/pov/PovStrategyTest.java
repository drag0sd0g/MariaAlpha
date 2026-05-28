package com.mariaalpha.strategyengine.strategy.pov;

import static com.mariaalpha.strategyengine.strategy.pov.PovStrategy.MARKET_ZONE;
import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PovStrategyTest {

  private static final String AAPL = "AAPL";
  private static final LocalTime WINDOW_START = LocalTime.of(9, 30);
  private static final LocalTime WINDOW_END = LocalTime.of(16, 0);
  private static final double TEN_PCT = 0.10;

  private PovStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new PovStrategy();
  }

  @Test
  void nameReturnsPov() {
    assertThat(strategy.name()).isEqualTo("POV");
  }

  @Test
  void defaultParametersHavePosixSensibleValues() {
    var params = strategy.getParameters();
    assertThat(params.get("participationRate")).isEqualTo(0.10);
    assertThat(params.get("side")).isEqualTo("BUY");
    assertThat(params.get("startTime")).isEqualTo("09:30");
    assertThat(params.get("endTime")).isEqualTo("16:00");
    assertThat(params.get("minClipSize")).isEqualTo(1);
    assertThat(params.get("targetQuantity")).isEqualTo(0);
    assertThat(params.get("emittedQuantity")).isEqualTo(0L);
    assertThat(params.get("cumulativeMarketVolume")).isEqualTo(0L);
  }

  @Test
  void evaluateReturnsEmptyBeforeConfiguration() {
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void evaluateReturnsEmptyBeforeStartTime() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    strategy.onTick(tradeTick(AAPL, etInstant(9, 0), "178.50", 5_000L));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void preStartTradeVolumeIsIgnored() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    // Pre-open prints don't count toward cumulative volume
    strategy.onTick(tradeTick(AAPL, etInstant(9, 0), "178.50", 50_000L));
    // First in-window tick has small size so participation target is 0
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.55", 5L));
    var signal = strategy.evaluate(AAPL);
    // 10% × 5 shares = 0.5, floors to 0, no emission
    assertThat(signal).isEmpty();
    assertThat(strategy.getParameters().get("cumulativeMarketVolume")).isEqualTo(5L);
  }

  @Test
  void firstQualifyingTickEmitsParticipationClip() {
    // 1000-share parent at 10% participation
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    // 5000 shares trade on the tape → strategy should emit 500
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 5_000L));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    var orderSignal = signal.get();
    assertThat(orderSignal.symbol()).isEqualTo(AAPL);
    assertThat(orderSignal.side()).isEqualTo(Side.BUY);
    assertThat(orderSignal.quantity()).isEqualTo(500);
    assertThat(orderSignal.orderType()).isEqualTo(OrderType.LIMIT);
    assertThat(orderSignal.limitPrice()).isEqualByComparingTo(new BigDecimal("178.54"));
    assertThat(orderSignal.strategyName()).isEqualTo("POV");
  }

  @Test
  void cumulativeParticipationTracksMarketVolume() {
    configurePov(10_000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    // Tick 1: 1000 shares → 100 share clip
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 1_000L));
    assertThat(strategy.evaluate(AAPL).orElseThrow().quantity()).isEqualTo(100);

    // Tick 2: another 1000 shares → 100 more shares (cumulative 200, emitted 200)
    strategy.onTick(tradeTick(AAPL, etInstant(10, 1), "178.55", 1_000L));
    assertThat(strategy.evaluate(AAPL).orElseThrow().quantity()).isEqualTo(100);

    // Tick 3: 5000 more → 500 more shares (cumulative 700)
    strategy.onTick(tradeTick(AAPL, etInstant(10, 2), "178.56", 5_000L));
    assertThat(strategy.evaluate(AAPL).orElseThrow().quantity()).isEqualTo(500);

    assertThat(strategy.getParameters().get("emittedQuantity")).isEqualTo(700L);
  }

  @Test
  void smallVolumeBelowMinClipDeferred() {
    configurePov(1000, Side.BUY, TEN_PCT, 100, Integer.MAX_VALUE);
    // 500 shares × 10% = 50 < minClipSize=100 → no emission
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 500L));
    assertThat(strategy.evaluate(AAPL)).isEmpty();

    // Another 500 shares → cumulative 1000 × 10% = 100 ≥ minClipSize → emit 100
    strategy.onTick(tradeTick(AAPL, etInstant(10, 1), "178.55", 500L));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(100);
  }

  @Test
  void maxClipSizeCapsLargeBlockPrints() {
    // 1M share block print would normally trigger a 100k clip; cap at 1000 instead.
    configurePov(200_000, Side.BUY, TEN_PCT, 1, 1_000);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 1_000_000L));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1_000);

    // Next tick with same cumulative volume should drain the remainder progressively
    strategy.onTick(tradeTick(AAPL, etInstant(10, 1), "178.55", 0L));
    var followUp = strategy.evaluate(AAPL);
    assertThat(followUp).isPresent();
    assertThat(followUp.get().quantity()).isEqualTo(1_000);
  }

  @Test
  void cumulativeEmissionCappedByTargetQuantity() {
    // 1000-share parent. Tape trades 50k → 10% would suggest 5000, but parent caps it at 1000.
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 50_000L));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(1000);

    // No further emissions; parent fully placed
    strategy.onTick(tradeTick(AAPL, etInstant(10, 1), "178.55", 10_000L));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void quoteTicksDoNotIncrementVolume() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    // A quote tick has bidSize/askSize but those are not executed volume
    strategy.onTick(quoteTick(AAPL, etInstant(10, 0), "178.50", "178.54"));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
    assertThat(strategy.getParameters().get("cumulativeMarketVolume")).isEqualTo(0L);
  }

  @Test
  void evaluateIgnoresTicksForDifferentSymbol() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 5_000L));
    assertThat(strategy.evaluate("MSFT")).isEmpty();
  }

  @Test
  void sellSideUsesBidPrice() {
    configurePov(1000, Side.SELL, TEN_PCT, 1, Integer.MAX_VALUE);
    strategy.onTick(tradeTickWithQuote(AAPL, etInstant(10, 0), "178.50", "178.54", 5_000L));

    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().side()).isEqualTo(Side.SELL);
    assertThat(signal.get().limitPrice()).isEqualByComparingTo(new BigDecimal("178.50"));
  }

  @Test
  void tradePriceFallbackWhenNoQuote() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.52", 5_000L));
    var signal = strategy.evaluate(AAPL);
    assertThat(signal).isPresent();
    assertThat(signal.get().limitPrice()).isEqualByComparingTo(new BigDecimal("178.52"));
  }

  @Test
  void emitsMarketSweepAtEndTimeWhenIncomplete() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    // Execute 500 shares early
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 5_000L));
    var first = strategy.evaluate(AAPL);
    assertThat(first.orElseThrow().quantity()).isEqualTo(500);

    // Jump past end-time tick with little volume
    strategy.onTick(tradeTick(AAPL, etInstant(16, 5), "180.00", 100L));
    var sweep = strategy.evaluate(AAPL);
    assertThat(sweep).isPresent();
    assertThat(sweep.get().quantity()).isEqualTo(500);
    assertThat(sweep.get().orderType()).isEqualTo(OrderType.MARKET);
    assertThat(sweep.get().limitPrice()).isNull();
  }

  @Test
  void sweepEmitsOnlyOnce() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    strategy.onTick(tradeTick(AAPL, etInstant(16, 5), "180.00", 100L));
    assertThat(strategy.evaluate(AAPL)).isPresent();
    strategy.onTick(tradeTick(AAPL, etInstant(16, 6), "180.00", 100L));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void noSweepWhenFullyExecuted() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    // Drive cumulative volume above 10k so 10% × 10k = 1000 = parent
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 10_000L));
    assertThat(strategy.evaluate(AAPL).orElseThrow().quantity()).isEqualTo(1000);

    strategy.onTick(tradeTick(AAPL, etInstant(16, 5), "180.00", 100L));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void postWindowTradeVolumeIgnored() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    // Trades after endTime should NOT bump cumulativeMarketVolume
    strategy.onTick(tradeTick(AAPL, etInstant(16, 30), "180.00", 100_000L));
    assertThat(strategy.getParameters().get("cumulativeMarketVolume")).isEqualTo(0L);
  }

  @Test
  void zeroParticipationRateSilencesStrategy() {
    configurePov(1000, Side.BUY, 0.0, 1, Integer.MAX_VALUE);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 1_000_000L));
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void halfParticipationRateMatchesHalfTheTape() {
    configurePov(10_000, Side.BUY, 0.50, 1, Integer.MAX_VALUE);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 10_000L));
    assertThat(strategy.evaluate(AAPL).orElseThrow().quantity()).isEqualTo(5_000);
  }

  @Test
  void getParametersReflectsLiveProgress() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 3_000L));
    strategy.evaluate(AAPL);

    var params = strategy.getParameters();
    assertThat(params.get("cumulativeMarketVolume")).isEqualTo(3_000L);
    assertThat(params.get("emittedQuantity")).isEqualTo(300L);
    assertThat(params.get("targetQuantity")).isEqualTo(1000);
    assertThat(params.get("participationRate")).isEqualTo(0.10);
  }

  @Test
  void updateParametersResetsState() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    strategy.onTick(tradeTick(AAPL, etInstant(10, 0), "178.54", 5_000L));
    strategy.evaluate(AAPL); // emits 500

    // Reconfigure → state resets
    configurePov(2000, Side.SELL, 0.20, 1, Integer.MAX_VALUE);
    var params = strategy.getParameters();
    assertThat(params.get("emittedQuantity")).isEqualTo(0L);
    assertThat(params.get("cumulativeMarketVolume")).isEqualTo(0L);
    assertThat(params.get("targetQuantity")).isEqualTo(2000);
    assertThat(params.get("side")).isEqualTo("SELL");
    assertThat(params.get("participationRate")).isEqualTo(0.20);
  }

  @Test
  void partialParameterUpdatePreservesOthers() {
    configurePov(1000, Side.BUY, TEN_PCT, 1, Integer.MAX_VALUE);
    strategy.updateParameters(Map.of("participationRate", 0.25));
    var params = strategy.getParameters();
    assertThat(params.get("participationRate")).isEqualTo(0.25);
    assertThat(params.get("targetQuantity")).isEqualTo(1000);
    assertThat(params.get("side")).isEqualTo("BUY");
  }

  @Test
  void fullSessionSimulationApproximatesTargetParticipation() {
    // Simulate a session: 100 trades of 10k shares each → 1M cumulative volume.
    // At 10% participation, the strategy should emit ~10% × 1M = 100k shares (capped at parent).
    configurePov(120_000, Side.BUY, TEN_PCT, 100, Integer.MAX_VALUE);

    var baseDate = LocalDate.of(2026, 3, 24);
    long totalEmitted = 0L;
    int signalCount = 0;
    for (int i = 0; i < 100; i++) {
      var ts =
          ZonedDateTime.of(baseDate, LocalTime.of(10, 0).plusMinutes(i), MARKET_ZONE).toInstant();
      strategy.onTick(
          new MarketTick(
              AAPL,
              ts,
              EventType.TRADE,
              new BigDecimal("178.50"),
              10_000L,
              new BigDecimal("178.48"),
              new BigDecimal("178.52"),
              100L,
              80L,
              0L,
              DataSource.ALPACA,
              false));
      var signal = strategy.evaluate(AAPL);
      if (signal.isPresent()) {
        totalEmitted += signal.get().quantity();
        signalCount++;
      }
    }
    // Cumulative emitted ≈ 100k (10% of 1M traded), well within the 120k parent.
    assertThat(totalEmitted).isEqualTo(100_000L);
    assertThat(signalCount).isGreaterThan(0);
    assertThat((long) strategy.getParameters().get("emittedQuantity")).isEqualTo(totalEmitted);
  }

  private static Instant etInstant(int hour, int minute) {
    return ZonedDateTime.of(LocalDate.of(2026, 3, 24), LocalTime.of(hour, minute), MARKET_ZONE)
        .toInstant();
  }

  private static MarketTick tradeTick(String symbol, Instant ts, String price, long size) {
    return new MarketTick(
        symbol,
        ts,
        EventType.TRADE,
        new BigDecimal(price),
        size,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0L,
        0L,
        0L,
        DataSource.ALPACA,
        false);
  }

  private static MarketTick tradeTickWithQuote(
      String symbol, Instant ts, String bid, String ask, long size) {
    return new MarketTick(
        symbol,
        ts,
        EventType.TRADE,
        new BigDecimal(ask), // last-trade price ~= ask for a buy print
        size,
        new BigDecimal(bid),
        new BigDecimal(ask),
        100L,
        80L,
        0L,
        DataSource.ALPACA,
        false);
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

  private void configurePov(
      int targetQty, Side side, double participation, int minClip, int maxClip) {
    var params = new HashMap<String, Object>();
    params.put("targetQuantity", targetQty);
    params.put("side", side.name());
    params.put("startTime", WINDOW_START.toString());
    params.put("endTime", WINDOW_END.toString());
    params.put("participationRate", participation);
    params.put("minClipSize", minClip);
    params.put("maxClipSize", maxClip);
    strategy.updateParameters(params);
  }
}
