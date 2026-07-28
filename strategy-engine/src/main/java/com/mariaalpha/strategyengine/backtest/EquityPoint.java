package com.mariaalpha.strategyengine.backtest;

import java.math.BigDecimal;
import java.time.Instant;

/** One marked-to-market equity sample on the backtest equity curve. */
public record EquityPoint(Instant timestamp, BigDecimal equity) {}
