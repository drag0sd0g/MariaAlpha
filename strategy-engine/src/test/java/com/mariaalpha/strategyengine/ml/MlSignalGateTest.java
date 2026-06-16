package com.mariaalpha.strategyengine.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mariaalpha.proto.signal.Direction;
import com.mariaalpha.strategyengine.config.MlConfig;
import com.mariaalpha.strategyengine.config.MlConfig.SizingMode;
import com.mariaalpha.strategyengine.config.MlConfig.VetoMode;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MlSignalGateTest {

  private static final OrderSignal BUY =
      new OrderSignal(
          "AAPL", Side.BUY, 100, OrderType.LIMIT, new BigDecimal("178.50"), "VWAP", Instant.now());
  private static final OrderSignal SELL =
      new OrderSignal(
          "AAPL", Side.SELL, 100, OrderType.LIMIT, new BigDecimal("178.50"), "VWAP", Instant.now());

  private static MlConfig strict() {
    return new MlConfig("h", 1, 0.7, VetoMode.STRICT, false, SizingMode.NONE, 0.2, 0.5, 1.5);
  }

  @Test
  void noMlResultPassesThrough() {
    var decision = new MlSignalGate(strict()).decide(BUY, Optional.empty());
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.NO_ML);
    assertThat(decision.signal()).contains(BUY);
    assertThat(decision.quantityScale()).isEqualTo(1.0);
  }

  @Test
  void lowConfidencePassesThrough() {
    var ml = Optional.of(new MlSignalResult(Direction.SHORT, 0.5, 0.0));
    var decision = new MlSignalGate(strict()).decide(BUY, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.LOW_CONFIDENCE);
    assertThat(decision.signal()).contains(BUY);
  }

  @Test
  void exactThresholdTreatedAsLowConfidence() {
    var ml = Optional.of(new MlSignalResult(Direction.SHORT, 0.7, 0.0));
    var decision = new MlSignalGate(strict()).decide(BUY, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.LOW_CONFIDENCE);
  }

  @Test
  void highConfidenceAgreeingLongConfirmsBuy() {
    var ml = Optional.of(new MlSignalResult(Direction.LONG, 0.9, 0.0));
    var decision = new MlSignalGate(strict()).decide(BUY, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.CONFIRMED);
    assertThat(decision.signal()).contains(BUY);
  }

  @Test
  void highConfidenceAgreeingShortConfirmsSell() {
    var ml = Optional.of(new MlSignalResult(Direction.SHORT, 0.9, 0.0));
    var decision = new MlSignalGate(strict()).decide(SELL, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.CONFIRMED);
  }

  @Test
  void highConfidenceContradictionVetoesInStrictMode() {
    var ml = Optional.of(new MlSignalResult(Direction.SHORT, 0.9, 0.0));
    var decision = new MlSignalGate(strict()).decide(BUY, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.VETOED);
    assertThat(decision.signal()).isEmpty();
  }

  @Test
  void highConfidenceContradictionPermissiveMarksOverridden() {
    var permissive =
        new MlConfig("h", 1, 0.7, VetoMode.PERMISSIVE, false, SizingMode.NONE, 0.2, 0.5, 1.5);
    var ml = Optional.of(new MlSignalResult(Direction.SHORT, 0.9, 0.0));
    var decision = new MlSignalGate(permissive).decide(BUY, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.OVERRIDDEN);
    assertThat(decision.signal()).contains(BUY);
  }

  @Test
  void offModeNeverSuppresses() {
    var off = new MlConfig("h", 1, 0.7, VetoMode.OFF, false, SizingMode.NONE, 0.2, 0.5, 1.5);
    var ml = Optional.of(new MlSignalResult(Direction.SHORT, 0.95, 0.0));
    var decision = new MlSignalGate(off).decide(BUY, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.OVERRIDDEN);
    assertThat(decision.signal()).contains(BUY);
  }

  @Test
  void neutralPassesByDefault() {
    var ml = Optional.of(new MlSignalResult(Direction.NEUTRAL, 0.9, 0.0));
    var decision = new MlSignalGate(strict()).decide(BUY, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.NEUTRAL_PASS);
    assertThat(decision.signal()).contains(BUY);
  }

  @Test
  void neutralSuppressesWhenConfigured() {
    var neutralStrict =
        new MlConfig("h", 1, 0.7, VetoMode.STRICT, true, SizingMode.NONE, 0.2, 0.5, 1.5);
    var ml = Optional.of(new MlSignalResult(Direction.NEUTRAL, 0.9, 0.0));
    var decision = new MlSignalGate(neutralStrict).decide(BUY, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.VETOED);
  }

  @Test
  void neutralSuppressOnlyAppliesInStrictMode() {
    var neutralPermissive =
        new MlConfig("h", 1, 0.7, VetoMode.PERMISSIVE, true, SizingMode.NONE, 0.2, 0.5, 1.5);
    var ml = Optional.of(new MlSignalResult(Direction.NEUTRAL, 0.9, 0.0));
    var decision = new MlSignalGate(neutralPermissive).decide(BUY, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.NEUTRAL_PASS);
  }

  @Test
  void sizingScaledScalesQuantityUp() {
    var sizing =
        new MlConfig("h", 1, 0.7, VetoMode.STRICT, false, SizingMode.SCALED, 0.20, 0.50, 1.50);
    var ml = Optional.of(new MlSignalResult(Direction.LONG, 0.9, 0.30));
    var decision = new MlSignalGate(sizing).decide(BUY, ml);
    assertThat(decision.outcome()).isEqualTo(MlGateDecision.Outcome.CONFIRMED);
    assertThat(decision.quantityScale()).isCloseTo(1.5, within(1e-9));
    assertThat(decision.signal().orElseThrow().quantity()).isEqualTo(150);
  }

  @Test
  void sizingClampedToLowerBound() {
    var sizing =
        new MlConfig("h", 1, 0.7, VetoMode.STRICT, false, SizingMode.SCALED, 0.20, 0.50, 1.50);
    var ml = Optional.of(new MlSignalResult(Direction.LONG, 0.9, 0.05));
    var decision = new MlSignalGate(sizing).decide(BUY, ml);
    assertThat(decision.quantityScale()).isEqualTo(0.5);
    assertThat(decision.signal().orElseThrow().quantity()).isEqualTo(50);
  }

  @Test
  void sizingClampedToUpperBound() {
    var sizing =
        new MlConfig("h", 1, 0.7, VetoMode.STRICT, false, SizingMode.SCALED, 0.20, 0.50, 1.50);
    var ml = Optional.of(new MlSignalResult(Direction.LONG, 0.9, 0.99));
    var decision = new MlSignalGate(sizing).decide(BUY, ml);
    assertThat(decision.quantityScale()).isEqualTo(1.5);
  }

  @Test
  void sizingDisabledWhenModeNone() {
    var noneMode =
        new MlConfig("h", 1, 0.7, VetoMode.STRICT, false, SizingMode.NONE, 0.20, 0.50, 1.50);
    var ml = Optional.of(new MlSignalResult(Direction.LONG, 0.9, 0.99));
    var decision = new MlSignalGate(noneMode).decide(BUY, ml);
    assertThat(decision.quantityScale()).isEqualTo(1.0);
    assertThat(decision.signal().orElseThrow().quantity()).isEqualTo(100);
  }

  @Test
  void sizingNoOpForZeroRecommendation() {
    var sizing =
        new MlConfig("h", 1, 0.7, VetoMode.STRICT, false, SizingMode.SCALED, 0.20, 0.50, 1.50);
    var ml = Optional.of(new MlSignalResult(Direction.LONG, 0.9, 0.0));
    var decision = new MlSignalGate(sizing).decide(BUY, ml);
    assertThat(decision.quantityScale()).isEqualTo(1.0);
    assertThat(decision.signal().orElseThrow().quantity()).isEqualTo(100);
  }
}
