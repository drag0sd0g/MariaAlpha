package com.mariaalpha.strategyengine.backtest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A backtest request. {@code strategyName} and {@code symbols} are required; everything else falls
 * back to configured defaults. Supply either {@code lookbackDays} or an explicit {@code
 * from}/{@code to} window.
 *
 * @param strategyName registered strategy to evaluate (e.g. {@code MOMENTUM})
 * @param symbols symbols to replay; each gets its own isolated strategy instance
 * @param lookbackDays calendar days of history back from now (ignored if {@code from} is set)
 * @param from explicit window start (inclusive)
 * @param to explicit window end (exclusive; defaults to now)
 * @param parameters strategy parameter overrides applied before the run
 * @param useMlGate route signals through the live ML signal gate (non-deterministic; default false)
 * @param slippageBps override modelled slippage for this run
 * @param initialCash starting cash (defaults to 100,000)
 */
public record BacktestRequest(
    String strategyName,
    List<String> symbols,
    Integer lookbackDays,
    Instant from,
    Instant to,
    Map<String, Object> parameters,
    boolean useMlGate,
    Double slippageBps,
    BigDecimal initialCash) {

  public BacktestRequest {
    symbols = symbols == null ? List.of() : List.copyOf(symbols);
    parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
  }
}
