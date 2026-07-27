package com.mariaalpha.strategyengine.backtest.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.strategyengine.backtest.BacktestProperties;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BarToTickSynthesizerTest {

  private static final Instant T = Instant.parse("2026-03-24T14:30:00Z");

  private final BarToTickSynthesizer synthesizer =
      new BarToTickSynthesizer(new BacktestProperties(null, 0, 1.0, 2.0, null));

  private static MinuteBar bar(String o, String h, String l, String c, long v) {
    return new MinuteBar(
        "AAPL",
        T,
        new BigDecimal(o),
        new BigDecimal(h),
        new BigDecimal(l),
        new BigDecimal(c),
        v,
        new BigDecimal(c));
  }

  @Test
  void emitsFourTicksPerBarSpacedAcrossTheMinute() {
    var ticks = synthesizer.toTicks(List.of(bar("100", "105", "98", "103", 1000)));

    assertThat(ticks).hasSize(4);
    assertThat(ticks).allSatisfy(t -> assertThat(t.eventType()).isEqualTo(EventType.TRADE));
    assertThat(ticks.stream().map(MarketTick::timestamp))
        .containsExactly(T, T.plusSeconds(15), T.plusSeconds(30), T.plusSeconds(45));
  }

  @Test
  void upBarTraversesOpenLowHighClose() {
    var ticks = synthesizer.toTicks(List.of(bar("100", "105", "98", "103", 1000)));
    assertThat(ticks.stream().map(t -> t.price().doubleValue()))
        .containsExactly(100.0, 98.0, 105.0, 103.0);
  }

  @Test
  void downBarTraversesOpenHighLowClose() {
    var ticks = synthesizer.toTicks(List.of(bar("100", "105", "98", "97", 1000)));
    assertThat(ticks.stream().map(t -> t.price().doubleValue()))
        .containsExactly(100.0, 105.0, 98.0, 97.0);
  }

  @Test
  void conservesBarVolumeAndAccumulatesCumulativeVolume() {
    var ticks =
        synthesizer.toTicks(
            List.of(bar("100", "105", "98", "103", 1000), bar("103", "104", "101", "102", 500)));

    long totalSize = ticks.stream().mapToLong(MarketTick::size).sum();
    assertThat(totalSize).isEqualTo(1500);
    // cumulativeVolume is monotonic non-decreasing and ends at the grand total.
    assertThat(ticks.get(ticks.size() - 1).cumulativeVolume()).isEqualTo(1500);
    long prev = 0;
    for (var tick : ticks) {
      assertThat(tick.cumulativeVolume()).isGreaterThanOrEqualTo(prev);
      prev = tick.cumulativeVolume();
    }
  }

  @Test
  void synthesizesBidBelowAndAskAboveEachPrice() {
    var ticks = synthesizer.toTicks(List.of(bar("100", "105", "98", "103", 1000)));
    assertThat(ticks)
        .allSatisfy(
            t -> {
              assertThat(t.bidPrice()).isLessThan(t.price());
              assertThat(t.askPrice()).isGreaterThan(t.price());
            });
  }

  @Test
  void emptyInputProducesNoTicks() {
    assertThat(synthesizer.toTicks(List.of())).isEmpty();
  }
}
