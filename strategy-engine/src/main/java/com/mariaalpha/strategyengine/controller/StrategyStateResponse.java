package com.mariaalpha.strategyengine.controller;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Aggregated per-symbol view consumed by the Strategy Control page: the active strategy binding
 * plus the latest ML signal and regime classification. All ML fields are nullable — when the model
 * is unavailable or the symbol has no rolling window yet, the UI gracefully degrades to "—".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrategyStateResponse(
    String symbol, String activeStrategy, Signal mlSignal, Regime mlRegime) {

  public record Signal(String direction, double confidence) {}

  public record Regime(String regime, double confidence) {}
}
