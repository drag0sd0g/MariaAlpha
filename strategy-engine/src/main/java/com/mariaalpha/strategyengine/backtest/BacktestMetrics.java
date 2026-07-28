package com.mariaalpha.strategyengine.backtest;

import java.math.BigDecimal;

/**
 * Headline performance statistics for a backtest run.
 *
 * @param totalReturnPct total return over the run, percent of initial equity
 * @param sharpe annualised Sharpe ratio computed from per-minute equity returns (risk-free = 0)
 * @param maxDrawdownPct largest peak-to-trough equity decline, percent
 * @param hitRate fraction of closed trades that realised positive P&L, in [0, 1]
 * @param closedTrades number of position-reducing trades that realised P&L
 * @param totalFills total number of simulated fills
 * @param avgSlippageBps mean execution slippage against arrival midpoint, basis points
 */
public record BacktestMetrics(
    double totalReturnPct,
    double sharpe,
    double maxDrawdownPct,
    double hitRate,
    int closedTrades,
    int totalFills,
    double avgSlippageBps,
    BigDecimal initialEquity,
    BigDecimal finalEquity,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl) {}
