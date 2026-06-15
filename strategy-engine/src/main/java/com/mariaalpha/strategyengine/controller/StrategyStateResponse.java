package com.mariaalpha.strategyengine.controller;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrategyStateResponse(
    String symbol, String activeStrategy, Signal mlSignal, Regime mlRegime) {

  public record Signal(String direction, double confidence) {}

  public record Regime(String regime, double confidence) {}
}
