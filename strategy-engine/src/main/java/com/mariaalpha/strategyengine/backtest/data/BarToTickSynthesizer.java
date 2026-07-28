package com.mariaalpha.strategyengine.backtest.data;

import com.mariaalpha.strategyengine.backtest.BacktestProperties;
import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Expands each historical 1-minute OHLCV bar into a short, ordered stream of {@link MarketTick}s so
 * the tick-driven strategies can consume real historical price action.
 *
 * <p>Alpaca bars carry no bid/ask, so a spread is modelled around each price ({@code spreadBps}).
 * Within a bar the traversal follows the conventional intrabar path — up bars go
 * open→low→high→close and down bars go open→high→low→close — spreading four trades across the
 * minute and splitting the bar's volume across them. This gives faithful price movement at 1-minute
 * resolution; the synthesized spread is the reason alpha P&L is "indicative" until a real
 * trades+quotes tape is wired in.
 */
@Component
public class BarToTickSynthesizer {

  private static final int TICKS_PER_BAR = 4;
  private static final long SECONDS_PER_BAR = 60;
  private static final long QUOTE_SIZE = 100;
  private static final BigDecimal MIN_HALF_SPREAD = new BigDecimal("0.01");

  private final BigDecimal halfSpreadFraction;

  public BarToTickSynthesizer(BacktestProperties properties) {
    // half of the fractional spread: spreadBps / 10000 / 2
    this.halfSpreadFraction =
        BigDecimal.valueOf(properties.spreadBps())
            .divide(BigDecimal.valueOf(20000), 10, RoundingMode.HALF_UP);
  }

  /**
   * Converts one symbol's ordered bars into an ordered tick tape with running cumulative volume.
   */
  public List<MarketTick> toTicks(List<MinuteBar> bars) {
    var ticks = new ArrayList<MarketTick>(bars.size() * TICKS_PER_BAR);
    long cumulativeVolume = 0;
    for (var bar : bars) {
      var prices = intrabarPath(bar);
      long baseSize = bar.volume() / TICKS_PER_BAR;
      long remainder = bar.volume() - baseSize * TICKS_PER_BAR;
      for (int i = 0; i < TICKS_PER_BAR; i++) {
        long size = baseSize + (i == TICKS_PER_BAR - 1 ? remainder : 0);
        cumulativeVolume += size;
        var timestamp = bar.timestamp().plusSeconds(i * (SECONDS_PER_BAR / TICKS_PER_BAR));
        ticks.add(tick(bar.symbol(), timestamp, prices[i], size, cumulativeVolume));
      }
    }
    return ticks;
  }

  private static BigDecimal[] intrabarPath(MinuteBar bar) {
    boolean upBar = bar.close().compareTo(bar.open()) >= 0;
    return upBar
        ? new BigDecimal[] {bar.open(), bar.low(), bar.high(), bar.close()}
        : new BigDecimal[] {bar.open(), bar.high(), bar.low(), bar.close()};
  }

  private MarketTick tick(
      String symbol, java.time.Instant timestamp, BigDecimal price, long size, long cumVolume) {
    var half = price.multiply(halfSpreadFraction).setScale(4, RoundingMode.HALF_UP);
    if (half.compareTo(MIN_HALF_SPREAD) < 0) {
      half = MIN_HALF_SPREAD;
    }
    var bid = price.subtract(half);
    var ask = price.add(half);
    return new MarketTick(
        symbol,
        timestamp,
        EventType.TRADE,
        price,
        size,
        bid,
        ask,
        QUOTE_SIZE,
        QUOTE_SIZE,
        cumVolume,
        DataSource.ALPACA,
        false);
  }
}
