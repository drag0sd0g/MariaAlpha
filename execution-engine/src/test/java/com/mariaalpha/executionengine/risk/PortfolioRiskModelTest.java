package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PortfolioRiskModelTest {

  private static final Instant NOW = Instant.parse("2026-07-29T15:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void buildsDailyCovarianceFromAnnualVolatilitiesAndCorrelation() {
    var model = new PortfolioRiskModel();
    model.update(snapshot(0.5, NOW));

    // v = (1, 0) picks out column 0 of Sigma_d, so sigma = sigma_daily,0.
    double expectedDaily0 = 0.48 / Math.sqrt(252.0);
    assertThat(model.portfolioSigma(new double[] {1.0, 0.0}))
        .isCloseTo(expectedDaily0, within(1e-12));
    assertThat(model.dailyVolatility("NVDA")).isCloseTo(expectedDaily0, within(1e-12));
    assertThat(model.dailyVolatility("MSFT")).isCloseTo(0.24 / Math.sqrt(252.0), within(1e-12));
  }

  @Test
  void portfolioSigmaMatchesTheQuadraticFormByHand() {
    var model = new PortfolioRiskModel();
    double rho = 0.5;
    model.update(snapshot(rho, NOW));

    double s0 = 0.48 / Math.sqrt(252.0);
    double s1 = 0.24 / Math.sqrt(252.0);
    double v0 = 1_000_000.0;
    double v1 = -400_000.0;
    double expected =
        Math.sqrt(v0 * v0 * s0 * s0 + v1 * v1 * s1 * s1 + 2 * v0 * v1 * rho * s0 * s1);

    assertThat(model.portfolioSigma(new double[] {v0, v1})).isCloseTo(expected, within(1e-6));
  }

  @Test
  void hedgedBookAtPerfectCorrelationHasEssentiallyZeroSigma() {
    var model = new PortfolioRiskModel();
    model.update(
        new RiskModelSnapshot(
            "m",
            NOW,
            "test",
            "supplied",
            100,
            252.0,
            List.of("A", "B"),
            new double[] {0.3, 0.3},
            new double[][] {{1.0, 1.0}, {1.0, 1.0}}));

    assertThat(model.portfolioSigma(new double[] {1_000_000.0, -1_000_000.0}))
        .as("equal and opposite positions in perfectly correlated names carry no risk")
        .isCloseTo(0.0, within(1e-6));
  }

  /** The correctness proof for the O(N) fast path used by {@code IntradayVarCheck}. */
  @Test
  void sigmaAfterDeltaEqualsFullRecomputeAcrossRandomBooks() {
    var model = new PortfolioRiskModel();
    model.update(fiveSymbolSnapshot());
    // SplittableRandom rather than Random: seeded and deterministic like Random, but it also
    // sidesteps SpotBugs' DMI_RANDOM_USED_ONLY_ONCE, which misfires on the draws below even
    // though this generator is used 1,400 times.
    var random = new SplittableRandom(20260729L);

    for (int trial = 0; trial < 200; trial++) {
      double[] v = new double[5];
      for (int i = 0; i < v.length; i++) {
        v[i] = (random.nextDouble() - 0.5) * 20_000_000.0;
      }
      int k = random.nextInt(v.length);
      double delta = (random.nextDouble() - 0.5) * 5_000_000.0;

      double sigma = model.portfolioSigma(v);
      double[] covTimesV = model.covarianceTimesVector(v);
      double fast = model.sigmaAfterDelta(covTimesV, sigma, k, delta);

      double[] updated = v.clone();
      updated[k] += delta;
      double full = model.portfolioSigma(updated);

      assertThat(fast)
          .as("rank-1 update must equal a full recompute (trial %d, k=%d)", trial, k)
          .isCloseTo(full, within(Math.max(1e-6, full * 1e-9)));
    }
  }

  @Test
  void isUsableIsFalseBeforeAnyModelArrives() {
    var model = new PortfolioRiskModel();
    assertThat(model.isPresent()).isFalse();
    assertThat(model.isUsable(Duration.ofMinutes(15), CLOCK)).isFalse();
    assertThat(model.age(CLOCK)).isEqualTo(Duration.ZERO);
    assertThat(model.size()).isZero();
    assertThat(model.symbols()).isEmpty();
  }

  @Test
  void isUsableRespectsTheMaxAge() {
    var model = new PortfolioRiskModel();
    model.update(snapshot(0.5, NOW.minus(Duration.ofMinutes(10))));

    assertThat(model.isUsable(Duration.ofMinutes(15), CLOCK)).isTrue();
    assertThat(model.isUsable(Duration.ofMinutes(5), CLOCK)).isFalse();
    assertThat(model.age(CLOCK)).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void zeroMaxAgeDisablesTheStalenessCheck() {
    var model = new PortfolioRiskModel();
    model.update(snapshot(0.5, NOW.minus(Duration.ofDays(3))));
    assertThat(model.isUsable(Duration.ZERO, CLOCK)).isTrue();
  }

  @Test
  void futureDatedModelReportsZeroAgeRatherThanNegative() {
    var model = new PortfolioRiskModel();
    model.update(snapshot(0.5, NOW.plus(Duration.ofMinutes(5))));
    assertThat(model.age(CLOCK)).isEqualTo(Duration.ZERO);
    assertThat(model.isUsable(Duration.ofMinutes(1), CLOCK)).isTrue();
  }

  @Test
  void indexOfReturnsEmptyForAnUnmodelledSymbol() {
    var model = new PortfolioRiskModel();
    model.update(snapshot(0.5, NOW));
    assertThat(model.indexOf("NVDA")).hasValue(0);
    assertThat(model.indexOf("MSFT")).hasValue(1);
    assertThat(model.indexOf("TSLA")).isEmpty();
    assertThat(model.dailyVolatility("TSLA")).isZero();
  }

  @Test
  void mutatingTheCallersArraysDoesNotChangeTheCachedModel() {
    double[] vols = {0.48, 0.24};
    double[][] correlation = {{1.0, 0.5}, {0.5, 1.0}};
    var model = new PortfolioRiskModel();
    model.update(
        new RiskModelSnapshot(
            "m", NOW, "test", "supplied", 100, 252.0, List.of("NVDA", "MSFT"), vols, correlation));

    double before = model.portfolioSigma(new double[] {1_000_000.0, 1_000_000.0});
    vols[0] = 99.0;
    correlation[0][1] = -1.0;
    correlation[1][0] = -1.0;
    double after = model.portfolioSigma(new double[] {1_000_000.0, 1_000_000.0});

    assertThat(after).as("the model must have copied the arrays").isEqualTo(before);
  }

  @Test
  void vectorLengthMismatchIsRejected() {
    var model = new PortfolioRiskModel();
    model.update(snapshot(0.5, NOW));
    assertThatThrownBy(() -> model.portfolioSigma(new double[] {1.0}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model size");
    assertThatThrownBy(() -> model.sigmaAfterDelta(new double[] {1.0, 2.0}, 1.0, 5, 1.0))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void emptyModelDegradesGracefully() {
    var model = new PortfolioRiskModel();
    assertThat(model.portfolioSigma(new double[0])).isZero();
    assertThat(model.covarianceTimesVector(new double[0])).isEmpty();
    assertThat(model.sigmaAfterDelta(new double[0], 5.0, 0, 1.0)).isEqualTo(5.0);
    assertThat(model.modelId()).isNull();
  }

  private static RiskModelSnapshot snapshot(double correlation, Instant generatedAt) {
    return new RiskModelSnapshot(
        "cov-test",
        generatedAt,
        "ewma+ledoit_wolf",
        "sample+prior",
        240,
        252.0,
        List.of("NVDA", "MSFT"),
        new double[] {0.48, 0.24},
        new double[][] {{1.0, correlation}, {correlation, 1.0}});
  }

  private static RiskModelSnapshot fiveSymbolSnapshot() {
    double[] vols = {0.28, 0.24, 0.27, 0.55, 0.48};
    double[][] correlation = new double[5][5];
    for (int i = 0; i < 5; i++) {
      for (int j = 0; j < 5; j++) {
        correlation[i][j] = i == j ? 1.0 : 0.35 + 0.05 * Math.abs(i - j);
      }
    }
    return new RiskModelSnapshot(
        "cov-5",
        NOW,
        "ewma+ledoit_wolf",
        "sample+prior",
        500,
        252.0,
        List.of("AAPL", "MSFT", "GOOGL", "TSLA", "NVDA"),
        vols,
        correlation);
  }
}
