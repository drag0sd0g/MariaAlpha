package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validation is the only thing standing between a malformed model on the wire and a nonsense VaR
 * number in the pre-trade gate, so every rejection path gets a case.
 */
class RiskModelSnapshotTest {

  private static final Instant NOW = Instant.parse("2026-07-29T15:00:00Z");

  @Test
  void acceptsAWellFormedModel() {
    assertThatCode(() -> valid().validate()).doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingSymbols() {
    assertThatThrownBy(() -> with(List.of(), new double[0], new double[0][0]).validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no symbols");
  }

  @Test
  void rejectsMissingGeneratedAt() {
    var snapshot =
        new RiskModelSnapshot(
            "m",
            null,
            "e",
            "s",
            1,
            252.0,
            List.of("A"),
            new double[] {0.2},
            new double[][] {{1.0}});
    assertThatThrownBy(snapshot::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("generatedAt");
  }

  @Test
  void rejectsVolatilityLengthMismatch() {
    assertThatThrownBy(
            () ->
                with(List.of("A", "B"), new double[] {0.2}, new double[][] {{1.0, 0.0}, {0.0, 1.0}})
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("annualizedVolatility length");
  }

  @Test
  void rejectsNonSquareCorrelation() {
    assertThatThrownBy(
            () ->
                with(List.of("A", "B"), new double[] {0.2, 0.3}, new double[][] {{1.0, 0.0}})
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("correlation must have 2 rows");
  }

  @Test
  void rejectsRaggedCorrelationRow() {
    assertThatThrownBy(
            () ->
                with(List.of("A", "B"), new double[] {0.2, 0.3}, new double[][] {{1.0, 0.0}, {0.0}})
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("correlation row 1");
  }

  @Test
  void rejectsNonUnitDiagonal() {
    assertThatThrownBy(
            () ->
                with(
                        List.of("A", "B"),
                        new double[] {0.2, 0.3},
                        new double[][] {{0.9, 0.0}, {0.0, 1.0}})
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("correlation diagonal");
  }

  @Test
  void rejectsCorrelationOutsideMinusOneToOne() {
    assertThatThrownBy(
            () ->
                with(
                        List.of("A", "B"),
                        new double[] {0.2, 0.3},
                        new double[][] {{1.0, 1.4}, {1.4, 1.0}})
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("out of range");
  }

  @Test
  void rejectsAsymmetricCorrelation() {
    assertThatThrownBy(
            () ->
                with(
                        List.of("A", "B"),
                        new double[] {0.2, 0.3},
                        new double[][] {{1.0, 0.5}, {0.1, 1.0}})
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not symmetric");
  }

  @Test
  void rejectsNegativeOrNonFiniteVolatility() {
    assertThatThrownBy(
            () ->
                with(
                        List.of("A", "B"),
                        new double[] {-0.2, 0.3},
                        new double[][] {{1.0, 0.0}, {0.0, 1.0}})
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("annualizedVolatility[0]");

    assertThatThrownBy(
            () ->
                with(
                        List.of("A", "B"),
                        new double[] {Double.NaN, 0.3},
                        new double[][] {{1.0, 0.0}, {0.0, 1.0}})
                    .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("annualizedVolatility[0]");
  }

  @Test
  void rejectsNonPositiveTradingDays() {
    var snapshot =
        new RiskModelSnapshot(
            "m", NOW, "e", "s", 1, 0.0, List.of("A"), new double[] {0.2}, new double[][] {{1.0}});
    assertThatThrownBy(snapshot::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tradingDaysPerYear");
  }

  private static RiskModelSnapshot valid() {
    return with(
        List.of("NVDA", "MSFT"),
        new double[] {0.48, 0.24},
        new double[][] {{1.0, 0.42}, {0.42, 1.0}});
  }

  private static RiskModelSnapshot with(
      List<String> symbols, double[] vols, double[][] correlation) {
    return new RiskModelSnapshot(
        "cov-test",
        NOW,
        "ewma+ledoit_wolf",
        "sample+prior",
        240,
        252.0,
        symbols,
        vols,
        correlation);
  }
}
