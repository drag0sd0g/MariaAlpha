package com.mariaalpha.strategyengine.backtest.data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One historical 1-minute OHLCV bar as returned by Alpaca. {@code timestamp} is the start of the
 * bar interval (UTC).
 */
public record MinuteBar(
    String symbol,
    Instant timestamp,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume,
    BigDecimal vwap) {}
