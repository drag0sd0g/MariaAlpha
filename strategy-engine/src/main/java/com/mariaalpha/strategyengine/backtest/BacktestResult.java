package com.mariaalpha.strategyengine.backtest;

import java.time.Instant;
import java.util.List;

/**
 * The full result of a backtest run: run metadata, headline {@link BacktestMetrics}, the equity
 * curve, and the trade blotter. {@code reportPath} is populated once the HTML report has been
 * written.
 */
public record BacktestResult(
    String strategyName,
    List<String> symbols,
    Instant from,
    Instant to,
    int barsReplayed,
    int ticksReplayed,
    boolean mlGateUsed,
    String dataNote,
    BacktestMetrics metrics,
    List<EquityPoint> equityCurve,
    List<TradeRecord> trades,
    String reportPath) {

  public BacktestResult {
    symbols = symbols == null ? List.of() : List.copyOf(symbols);
    equityCurve = equityCurve == null ? List.of() : List.copyOf(equityCurve);
    trades = trades == null ? List.of() : List.copyOf(trades);
  }

  public BacktestResult withReportPath(String path) {
    return new BacktestResult(
        strategyName,
        symbols,
        from,
        to,
        barsReplayed,
        ticksReplayed,
        mlGateUsed,
        dataNote,
        metrics,
        equityCurve,
        trades,
        path);
  }
}
