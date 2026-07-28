package com.mariaalpha.strategyengine.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.strategyengine.backtest.data.AlpacaHistoricalClient;
import com.mariaalpha.strategyengine.backtest.data.BarToTickSynthesizer;
import com.mariaalpha.strategyengine.backtest.data.MinuteBar;
import com.mariaalpha.strategyengine.metrics.StrategyMetrics;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.ml.MlSignalGate;
import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.strategy.momentum.MomentumStrategy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BacktestEngineTest {

  private static final Instant T0 = Instant.parse("2026-03-24T14:30:00Z");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-29T00:00:00Z"), ZoneOffset.UTC);

  // Momentum tuned so a clean EMA crossover drives entries: RSI/volume/stop gates disabled,
  // short fast/slow periods so the cross fires quickly.
  private static final Map<String, Object> MOMENTUM_PARAMS =
      Map.ofEntries(
          Map.entry("fastPeriod", 2),
          Map.entry("slowPeriod", 4),
          Map.entry("rsiPeriod", 2),
          Map.entry("rsiOverbought", 101.0),
          Map.entry("rsiOversold", -1.0),
          Map.entry("volumeMultiplier", 0.0),
          Map.entry("stopLossPct", 0.0),
          Map.entry("warmupTrades", 4),
          Map.entry("tradeQuantity", 100),
          Map.entry("side", "BUY"));

  private final BarToTickSynthesizer synthesizer = new BarToTickSynthesizer(properties());
  private final StrategyRegistry registry = new StrategyRegistry(List.of(new MomentumStrategy()));
  private final BacktestStrategyFactory factory = new BacktestStrategyFactory(registry);

  private static BacktestProperties properties() {
    return new BacktestProperties(null, 5, 0.0, 2.0, null);
  }

  private BacktestEngine engineFor(AlpacaHistoricalClient client) {
    return new BacktestEngine(
        client,
        synthesizer,
        factory,
        mock(MlSignalClient.class),
        mock(MlSignalGate.class),
        mock(StrategyMetrics.class),
        properties(),
        CLOCK);
  }

  private static List<MinuteBar> flatBars(double... closes) {
    var bars = new ArrayList<MinuteBar>();
    for (int i = 0; i < closes.length; i++) {
      var price = BigDecimal.valueOf(closes[i]);
      bars.add(
          new MinuteBar("AAPL", T0.plusSeconds(i * 60L), price, price, price, price, 1000, price));
    }
    return bars;
  }

  private static BacktestRequest request() {
    return new BacktestRequest(
        "MOMENTUM", List.of("AAPL"), 5, null, null, MOMENTUM_PARAMS, false, 0.0, null);
  }

  @Test
  void risingMarketProducesAProfitableLongAndPositiveReturn() {
    var client = mock(AlpacaHistoricalClient.class);
    when(client.fetchMinuteBars(eq("AAPL"), any(), any()))
        .thenReturn(flatBars(100, 100, 100, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110));

    var result = engineFor(client).run(request());

    assertThat(result.barsReplayed()).isEqualTo(14);
    assertThat(result.metrics().totalFills()).isGreaterThanOrEqualTo(1);
    assertThat(result.metrics().totalReturnPct()).isPositive();
    assertThat(result.metrics().finalEquity()).isGreaterThan(result.metrics().initialEquity());
    assertThat(result.equityCurve()).isNotEmpty();
  }

  @Test
  void fallingMarketProducesNoLongEntriesForABuyStrategy() {
    var client = mock(AlpacaHistoricalClient.class);
    when(client.fetchMinuteBars(eq("AAPL"), any(), any()))
        .thenReturn(flatBars(100, 100, 100, 100, 99, 98, 97, 96, 95, 94, 93, 92));

    var result = engineFor(client).run(request());

    assertThat(result.metrics().totalFills()).isZero();
    assertThat(result.metrics().totalReturnPct()).isZero();
  }

  @Test
  void isDeterministicAcrossIdenticalRuns() {
    var client = mock(AlpacaHistoricalClient.class);
    when(client.fetchMinuteBars(eq("AAPL"), any(), any()))
        .thenReturn(flatBars(100, 100, 100, 100, 101, 102, 103, 102, 101, 103, 104, 105));

    var first = engineFor(client).run(request());
    var second = engineFor(client).run(request());

    assertThat(second.metrics().finalEquity()).isEqualByComparingTo(first.metrics().finalEquity());
    assertThat(second.metrics().totalFills()).isEqualTo(first.metrics().totalFills());
    assertThat(second.equityCurve()).hasSameSizeAs(first.equityCurve());
    assertThat(second.trades()).hasSameSizeAs(first.trades());
  }

  @Test
  void emptyDataYieldsFlatResultWithoutError() {
    var client = mock(AlpacaHistoricalClient.class);
    when(client.fetchMinuteBars(eq("AAPL"), any(), any())).thenReturn(List.of());

    var result = engineFor(client).run(request());

    assertThat(result.barsReplayed()).isZero();
    assertThat(result.metrics().totalReturnPct()).isZero();
    assertThat(result.equityCurve()).isEmpty();
  }
}
