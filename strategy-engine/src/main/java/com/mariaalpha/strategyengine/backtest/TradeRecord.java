package com.mariaalpha.strategyengine.backtest;

import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single simulated fill in the backtest blotter, with its execution slippage and realised P&L.
 */
public record TradeRecord(
    Instant timestamp,
    String symbol,
    Side side,
    int quantity,
    BigDecimal price,
    BigDecimal arrivalMid,
    double slippageBps,
    BigDecimal realizedPnl,
    boolean passive) {}
