package com.mariaalpha.strategyengine.backtest;

import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A simulated fill produced by {@link BacktestFillModel}. {@code arrivalMid} is the bid/ask
 * midpoint at the moment the originating signal was received, used to score execution slippage.
 */
public record BacktestFill(
    String symbol,
    Side side,
    int quantity,
    BigDecimal price,
    Instant timestamp,
    BigDecimal arrivalMid,
    boolean passive) {}
