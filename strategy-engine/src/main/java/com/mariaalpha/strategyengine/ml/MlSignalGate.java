package com.mariaalpha.strategyengine.ml;

import com.mariaalpha.proto.signal.Direction;
import com.mariaalpha.strategyengine.config.MlConfig;
import com.mariaalpha.strategyengine.config.MlConfig.SizingMode;
import com.mariaalpha.strategyengine.config.MlConfig.VetoMode;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.Side;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Confirmation / veto gate (FR-12, TDD §5.2.2).
 *
 * <p>The TDD specifies: <em>"if the ML signal confidence &gt; 0.7 and agrees with the strategy
 * direction, proceed. If &gt; 0.7 and contradicts, suppress. If ≤ 0.7, proceed with strategy signal
 * alone. Configurable via {@code config/strategy.yml}."</em>
 *
 * <p>Two additional configurable behaviours layer on top:
 *
 * <ul>
 *   <li>{@link VetoMode}: STRICT (default) suppresses contradictions; PERMISSIVE logs but lets the
 *       signal through; OFF disables the gate entirely.
 *   <li>{@link SizingMode}: when ML <strong>agrees</strong> with high confidence the gate can
 *       optionally <em>scale</em> the strategy's quantity by {@code recommendedSize /
 *       sizingNeutralFraction} (clamped between {@code sizingLowerBound} and {@code
 *       sizingUpperBound}), expressing the "adjusting urgency" half of FR-12.
 * </ul>
 *
 * <p>Pure decision logic — no I/O, no side effects other than the SLF4J log line. Stateless.
 */
@Component
public class MlSignalGate {

  private static final Logger LOG = LoggerFactory.getLogger(MlSignalGate.class);

  private final MlConfig config;

  public MlSignalGate(MlConfig config) {
    this.config = config;
  }

  public MlGateDecision decide(OrderSignal strategySignal, Optional<MlSignalResult> mlResult) {
    if (mlResult.isEmpty()) {
      return MlGateDecision.passThrough(MlGateDecision.Outcome.NO_ML, strategySignal);
    }
    var ml = mlResult.get();

    if (ml.confidence() <= config.confidenceThreshold()) {
      return MlGateDecision.passThrough(MlGateDecision.Outcome.LOW_CONFIDENCE, strategySignal);
    }

    if (ml.direction() == Direction.NEUTRAL) {
      if (config.neutralSuppress() && config.vetoMode() == VetoMode.STRICT) {
        LOG.info(
            "ML NEUTRAL@{} suppresses {} {} (neutral-suppress=true)",
            ml.confidence(),
            strategySignal.side(),
            strategySignal.symbol());
        return MlGateDecision.vetoed();
      }
      return MlGateDecision.passThrough(MlGateDecision.Outcome.NEUTRAL_PASS, strategySignal);
    }

    boolean agrees = directionsAgree(strategySignal.side(), ml.direction());
    if (agrees) {
      double scale = computeQuantityScale(ml);
      var resized = applyScale(strategySignal, scale);
      return MlGateDecision.confirmed(resized, scale);
    }

    // Contradiction
    return switch (config.vetoMode()) {
      case STRICT -> {
        LOG.info(
            "ML {}@{} contradicts strategy {} for {} — suppressing",
            ml.direction(),
            ml.confidence(),
            strategySignal.side(),
            strategySignal.symbol());
        yield MlGateDecision.vetoed();
      }
      case PERMISSIVE -> {
        LOG.warn(
            "ML {}@{} contradicts strategy {} for {} (veto-mode=PERMISSIVE — proceeding)",
            ml.direction(),
            ml.confidence(),
            strategySignal.side(),
            strategySignal.symbol());
        yield MlGateDecision.passThrough(MlGateDecision.Outcome.OVERRIDDEN, strategySignal);
      }
      case OFF -> MlGateDecision.passThrough(MlGateDecision.Outcome.OVERRIDDEN, strategySignal);
    };
  }

  private double computeQuantityScale(MlSignalResult ml) {
    if (config.sizingMode() != SizingMode.SCALED || ml.recommendedSize() <= 0.0) {
      return 1.0;
    }
    double rawScale = ml.recommendedSize() / config.sizingNeutralFraction();
    return clamp(rawScale, config.sizingLowerBound(), config.sizingUpperBound());
  }

  private static OrderSignal applyScale(OrderSignal signal, double scale) {
    if (scale == 1.0) {
      return signal;
    }
    int scaledQty = Math.max(1, (int) Math.round(signal.quantity() * scale));
    if (scaledQty == signal.quantity()) {
      return signal;
    }
    return new OrderSignal(
        signal.symbol(),
        signal.side(),
        scaledQty,
        signal.orderType(),
        signal.limitPrice(),
        signal.strategyName(),
        signal.timestamp());
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static boolean directionsAgree(Side side, Direction direction) {
    return (side == Side.BUY && direction == Direction.LONG)
        || (side == Side.SELL && direction == Direction.SHORT);
  }
}
