package com.mariaalpha.strategyengine.ml;

import com.mariaalpha.strategyengine.model.OrderSignal;
import java.util.Optional;

/**
 * Structured outcome from {@link MlSignalGate#decide}.
 *
 * <p>{@code outcome} captures the policy reason (CONFIRMED, VETOED, NO_ML, LOW_CONFIDENCE,
 * NEUTRAL_PASS). {@code signal} is the (possibly resized) signal to publish — empty when the gate
 * suppresses. {@code quantityScale} is the ratio applied to the strategy's original quantity (1.0
 * when no sizing adjustment ran).
 */
public record MlGateDecision(Outcome outcome, Optional<OrderSignal> signal, double quantityScale) {

  public enum Outcome {
    /** ML agreed with high confidence; signal proceeds, possibly resized. */
    CONFIRMED,
    /** ML contradicted with high confidence; STRICT mode suppresses. */
    VETOED,
    /** ML call failed / circuit breaker open / no result. Signal proceeds unchanged. */
    NO_ML,
    /** ML confidence below threshold; signal proceeds unchanged. */
    LOW_CONFIDENCE,
    /** ML returned NEUTRAL with high confidence and {@code neutral-suppress=false}. */
    NEUTRAL_PASS,
    /** ML contradicted but {@code veto-mode=PERMISSIVE} or OFF; signal proceeds. */
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
