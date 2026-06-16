package com.mariaalpha.strategyengine.strategy.momentum;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MomentumStrategyTest {

  private static final String AAPL = "AAPL";
  private static final Instant BASE = Instant.parse("2026-03-24T14:30:00Z");

  private MomentumStrategy strategy;
  private long seq;

  @BeforeEach
  void setUp() {
    strategy = new MomentumStrategy();
    seq = 0;
  }

  @Test
  void nameReturnsMomentum() {
    assertThat(strategy.name()).isEqualTo("MOMENTUM");
  }

  @Test
  void defaultParametersMatchSpec() {
    var params = strategy.getParameters();
    assertThat(params.get("fastPeriod")).isEqualTo(20);
    assertThat(params.get("slowPeriod")).isEqualTo(50);
    assertThat(params.get("rsiPeriod")).isEqualTo(14);
    assertThat(params.get("rsiOverbought")).isEqualTo(70.0);
    assertThat(params.get("rsiOversold")).isEqualTo(30.0);
    assertThat(params.get("volumeMultiplier")).isEqualTo(1.5);
    assertThat(params.get("side")).isEqualTo("BUY");
    assertThat(params.get("position")).isEqualTo("FLAT");
    assertThat(params.get("tradesObserved")).isEqualTo(0L);
  }

  @Test
  void evaluateEmptyBeforeAnyTick() {
    configure(Map.of());
    assertThat(strategy.evaluate(AAPL)).isEmpty();
  }

  @Test
  void bullishCrossoverWithConfirmationEntersLong() {
    configure(Map.of());

    assertThat(step(trade(AAPL, "100.00", 100, "99.98", "100.02"))).isEmpty();
    var signal = step(trade(AAPL, "101.00", 300, "100.98", "101.02"));

    assertThat(signal).isPresent();
    var order = signal.get();
    assertThat(order.symbol()).isEqualTo(AAPL);
    assertThat(order.side()).isEqualTo(Side.BUY);
    assertThat(order.quantity()).isEqualTo(100);
    assertThat(order.orderType()).isEqualTo(OrderType.LIMIT);
    assertThat(order.limitPrice()).isEqualByComparingTo(new BigDecimal("101.02"));
    assertThat(order.strategyName()).isEqualTo("MOMENTUM");
    assertThat(strategy.getParameters().get("position")).isEqualTo("LONG");
  }

  @Test
  void flatPriceSeriesNeverCrossesSoNoEntry() {
    configure(Map.of());
    for (int i = 0; i < 10; i++) {
      assertThat(step(trade(AAPL, "100.00", 200, "99.98", "100.02"))).isEmpty();
    }
  }

  @Test
  void entrySuppressedWhenVolumeNotConfirmed() {
    configure(Map.of("volumeMultiplier", 1.5));
    assertThat(step(trade(AAPL, "100.00", 1000, "99.98", "100.02"))).isEmpty();
    assertThat(step(trade(AAPL, "101.00", 100, "100.98", "101.02"))).isEmpty();
    assertThat(strategy.getParameters().get("position")).isEqualTo("FLAT");
  }

  @Test
  void entrySuppressedWhenRsiOverbought() {
    configure(Map.of("rsiPeriod", 2, "volumeMultiplier", 0.0));
    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    step(trade(AAPL, "99.00", 100, "98.98", "99.02"));
    step(trade(AAPL, "98.00", 100, "97.98", "98.02"));
    var signal = step(trade(AAPL, "102.00", 300, "101.98", "102.02"));

    assertThat(signal).as("bullish crossover but RSI overbought → no entry").isEmpty();
    assertThat(strategy.getParameters().get("position")).isEqualTo("FLAT");
    assertThat((double) strategy.getParameters().get("rsi")).isGreaterThanOrEqualTo(70.0);
  }

  @Test
  void noReentryWhileAlreadyLong() {
    configure(Map.of());
    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    assertThat(step(trade(AAPL, "101.00", 300, "100.98", "101.02"))).isPresent();
    assertThat(step(trade(AAPL, "102.00", 300, "101.98", "102.02"))).isEmpty();
    assertThat(step(trade(AAPL, "103.00", 300, "102.98", "103.02"))).isEmpty();
    assertThat(strategy.getParameters().get("position")).isEqualTo("LONG");
  }

  @Test
  void bearishCrossoverExitsLong() {
    configure(Map.of());
    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    assertThat(step(trade(AAPL, "101.00", 300, "100.98", "101.02"))).isPresent();

    var exit = step(trade(AAPL, "100.00", 300, "99.98", "100.02"));
    assertThat(exit).isPresent();
    var order = exit.get();
    assertThat(order.side()).isEqualTo(Side.SELL);
    assertThat(order.orderType()).isEqualTo(OrderType.MARKET);
    assertThat(order.limitPrice()).isNull();
    assertThat(order.quantity()).isEqualTo(100);
    assertThat(strategy.getParameters().get("position")).isEqualTo("FLAT");
  }

  @Test
  void rsiOverboughtExitsLong() {
    configure(Map.of("rsiPeriod", 2, "rsiOverbought", 70.0, "volumeMultiplier", 0.0));
    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    assertThat(step(trade(AAPL, "101.00", 100, "100.98", "101.02"))).isPresent();
    var exit = step(trade(AAPL, "102.00", 100, "101.98", "102.02"));
    assertThat(exit).isPresent();
    assertThat(exit.get().side()).isEqualTo(Side.SELL);
    assertThat(exit.get().orderType()).isEqualTo(OrderType.MARKET);
    assertThat(strategy.getParameters().get("position")).isEqualTo("FLAT");
  }

  @Test
  void stopLossExitsLongOnAdverseQuote() {
    configure(Map.of("stopLossPct", 1.0));
    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    assertThat(step(trade(AAPL, "101.00", 300, "100.98", "101.02"))).isPresent();

    var exit = step(quote(AAPL, "99.00", "99.04"));
    assertThat(exit).isPresent();
    assertThat(exit.get().side()).isEqualTo(Side.SELL);
    assertThat(exit.get().orderType()).isEqualTo(OrderType.MARKET);
    assertThat(strategy.getParameters().get("position")).isEqualTo("FLAT");
  }

  @Test
  void shortSideEntersOnBearishCrossover() {
    configure(Map.of("side", "SELL"));
    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    step(trade(AAPL, "101.00", 100, "100.98", "101.02"));
    var signal = step(trade(AAPL, "99.00", 300, "98.96", "99.04"));

    assertThat(signal).isPresent();
    var order = signal.get();
    assertThat(order.side()).isEqualTo(Side.SELL);
    assertThat(order.orderType()).isEqualTo(OrderType.LIMIT);
    assertThat(order.limitPrice()).isEqualByComparingTo(new BigDecimal("98.96"));
    assertThat(strategy.getParameters().get("position")).isEqualTo("SHORT");
  }

  @Test
  void shortSideExitsOnBullishCrossover() {
    configure(Map.of("side", "SELL"));
    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    step(trade(AAPL, "101.00", 100, "100.98", "101.02"));
    assertThat(step(trade(AAPL, "99.00", 300, "98.96", "99.04"))).isPresent();

    var exit = step(trade(AAPL, "101.00", 100, "100.98", "101.02"));
    assertThat(exit).isPresent();
    assertThat(exit.get().side()).isEqualTo(Side.BUY);
    assertThat(exit.get().orderType()).isEqualTo(OrderType.MARKET);
    assertThat(strategy.getParameters().get("position")).isEqualTo("FLAT");
  }

  @Test
  void quotesDoNotDriveIndicatorsOrSignals() {
    configure(Map.of());
    for (int i = 0; i < 10; i++) {
      assertThat(step(quote(AAPL, "100.98", "101.02"))).isEmpty();
    }
    assertThat(strategy.getParameters().get("tradesObserved")).isEqualTo(0L);
  }

  @Test
  void ignoresTicksForDifferentSymbol() {
    configure(Map.of());
    strategy.onTick(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    strategy.onTick(trade(AAPL, "101.00", 300, "100.98", "101.02"));
    assertThat(strategy.evaluate("MSFT")).isEmpty();
  }

  @Test
  void tradePriceFallbackWhenNoQuote() {
    configure(Map.of());
    step(trade(AAPL, "100.00", 100, "0", "0"));
    var signal = step(trade(AAPL, "101.00", 300, "0", "0"));
    assertThat(signal).isPresent();
    assertThat(signal.get().limitPrice()).isEqualByComparingTo(new BigDecimal("101.00"));
  }

  @Test
  void updateParametersResetsState() {
    configure(Map.of());
    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    assertThat(step(trade(AAPL, "101.00", 300, "100.98", "101.02"))).isPresent();
    assertThat(strategy.getParameters().get("position")).isEqualTo("LONG");

    configure(Map.of("tradeQuantity", 50));
    assertThat(strategy.getParameters().get("position")).isEqualTo("FLAT");
    assertThat(strategy.getParameters().get("tradesObserved")).isEqualTo(0L);

    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    var signal = step(trade(AAPL, "101.00", 300, "100.98", "101.02"));
    assertThat(signal).isPresent();
    assertThat(signal.get().quantity()).isEqualTo(50);
  }

  @Test
  void partialParameterUpdatePreservesOtherFields() {
    configure(Map.of("tradeQuantity", 100, "side", "BUY"));
    strategy.updateParameters(Map.of("tradeQuantity", 250));
    var params = strategy.getParameters();
    assertThat(params.get("tradeQuantity")).isEqualTo(250);
    assertThat(params.get("side")).isEqualTo("BUY");
    assertThat(params.get("fastPeriod")).isEqualTo(2);
    assertThat(params.get("slowPeriod")).isEqualTo(3);
  }

  @Test
  void getParametersExposesLiveIndicatorState() {
    configure(Map.of());
    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    step(trade(AAPL, "101.00", 300, "100.98", "101.02"));
    var params = strategy.getParameters();
    assertThat(params.get("tradesObserved")).isEqualTo(2L);
    assertThat((double) params.get("fastEma")).isGreaterThan((double) params.get("slowEma"));
  }

  @Test
  void zeroQuantityProducesNoSignal() {
    configure(Map.of("tradeQuantity", 0));
    step(trade(AAPL, "100.00", 100, "99.98", "100.02"));
    assertThat(step(trade(AAPL, "101.00", 300, "100.98", "101.02"))).isEmpty();
  }

  private Optional<OrderSignal> step(MarketTick tick) {
    strategy.onTick(tick);
    return strategy.evaluate(tick.symbol());
  }

  private void configure(Map<String, Object> overrides) {
    var params = new HashMap<String, Object>();
    params.put("fastPeriod", 2);
    params.put("slowPeriod", 3);
    params.put("warmupTrades", 2);
    params.put("rsiPeriod", 14);
    params.put("rsiOverbought", 70.0);
    params.put("rsiOversold", 30.0);
    params.put("volumeMultiplier", 1.5);
    params.put("volumeLookback", 20);
    params.put("tradeQuantity", 100);
    params.put("side", "BUY");
    params.put("stopLossPct", 50.0);
    params.putAll(overrides);
    strategy.updateParameters(params);
  }

  private MarketTick trade(String symbol, String price, long size, String bid, String ask) {
    return new MarketTick(
        symbol,
        BASE.plusSeconds(seq++),
        EventType.TRADE,
        new BigDecimal(price),
        size,
        new BigDecimal(bid),
        new BigDecimal(ask),
        100L,
        80L,
        0L,
        DataSource.SIMULATED,
        false);
  }

  private MarketTick quote(String symbol, String bid, String ask) {
    return new MarketTick(
        symbol,
        BASE.plusSeconds(seq++),
        EventType.QUOTE,
        BigDecimal.ZERO,
        0L,
        new BigDecimal(bid),
        new BigDecimal(ask),
        100L,
        80L,
        0L,
        DataSource.SIMULATED,
        false);
  }
}
