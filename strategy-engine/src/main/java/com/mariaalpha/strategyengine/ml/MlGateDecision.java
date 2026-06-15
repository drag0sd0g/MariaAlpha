package com.mariaalpha.strategyengine.ml;

import com.mariaalpha.strategyengine.model.OrderSignal;
import java.util.Optional;

public record MlGateDecision(Outcome outcome, Optional<OrderSignal> signal, double quantityScale) {

  public enum Outcome {
    CONFIRMED,
    VETOED,
    NO_ML,
    LOW_CONFIDENCE,
    NEUTRAL_PASS,
    OVERRIDDEN
  }

  public static MlGateDecision passThrough(Outcome outcome, OrderSignal signal) {
    return new MlGateDecision(outcome, Optional.of(signal), 1.0);
  }

  public static MlGateDecision confirmed(OrderSignal signal, double quantityScale) {
    return new MlGateDecision(Outcome.CONFIRMED, Optional.of(signal), quantityScale);
  }

  public static MlGateDecision vetoed() {
    return new MlGateDecision(Outcome.VETOED, Optional.empty(), 1.0);
  }
}
