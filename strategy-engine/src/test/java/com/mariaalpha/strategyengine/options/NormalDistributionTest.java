package com.mariaalpha.strategyengine.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class NormalDistributionTest {

  @Test
  void cdfAtZeroIsHalf() {
    assertThat(NormalDistribution.cdf(0.0)).isEqualTo(0.5);
  }

  @Test
  void cdfTextbookValues() {
    // A&S 26.2 reference values, accurate to the A&S 26.2.17 approximation budget (~7.5e-8)
    assertThat(NormalDistribution.cdf(1.0)).isCloseTo(0.8413447, within(1e-6));
    assertThat(NormalDistribution.cdf(-1.0)).isCloseTo(0.1586553, within(1e-6));
    assertThat(NormalDistribution.cdf(1.96)).isCloseTo(0.9750021, within(1e-6));
    assertThat(NormalDistribution.cdf(-1.96)).isCloseTo(0.0249979, within(1e-6));
    assertThat(NormalDistribution.cdf(3.0)).isCloseTo(0.9986501, within(1e-6));
  }

  @Test
  void cdfReflectionSymmetry() {
    for (double x : new double[] {0.1, 0.5, 1.2, 2.5, 4.0}) {
      assertThat(NormalDistribution.cdf(x) + NormalDistribution.cdf(-x))
          .as("Φ(%s) + Φ(-%s) == 1", x, x)
          .isCloseTo(1.0, within(1e-7));
    }
  }

  @Test
  void cdfTailsApproachLimits() {
    assertThat(NormalDistribution.cdf(8.0)).isCloseTo(1.0, within(1e-7));
    assertThat(NormalDistribution.cdf(-8.0)).isCloseTo(0.0, within(1e-7));
  }

  @Test
  void pdfPeaksAtZero() {
    double peak = 1.0 / Math.sqrt(2.0 * Math.PI);
    assertThat(NormalDistribution.pdf(0.0)).isCloseTo(peak, within(1e-12));
    assertThat(NormalDistribution.pdf(-1.0)).isCloseTo(NormalDistribution.pdf(1.0), within(1e-12));
    assertThat(NormalDistribution.pdf(1.0)).isCloseTo(0.2419707, within(1e-6));
  }
}
