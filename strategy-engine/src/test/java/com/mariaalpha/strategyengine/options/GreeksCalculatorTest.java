package com.mariaalpha.strategyengine.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GreeksCalculatorTest {

  private static final OptionsPricingConfig CONFIG =
      new OptionsPricingConfig(365.0, 100, 1.0e-6, 1.0e-4, 5.0);

  private final BlackScholesPricer pricer = new BlackScholesPricer();
  private final GreeksCalculator calculator = new GreeksCalculator(CONFIG);

  @Test
  void hullTextbookCallGreeks() {
    // Hull Ch.19 worked example, same inputs as the pricer test. Published reference values:
    //   delta = 0.7791 (= N(d1))
    //   gamma = 0.0498
    //   vega (annualised) = 8.79 → 0.0879 per 1% vol
    //   theta_call (annualised) ≈ −4.5532 → ÷365 ≈ −0.01247 per day
    //   rho_call (annualised) ≈ 13.98 → 0.1398 per 1% rate
    var contract = new OptionContract(42.0, 40.0, 0.5, 0.20, 0.10, 0.0, OptionType.CALL);
    var greeks = calculator.compute(contract);
    assertThat(greeks.delta()).isCloseTo(0.7791, within(0.001));
    assertThat(greeks.gamma()).isCloseTo(0.0498, within(0.001));
    assertThat(greeks.vega()).isCloseTo(0.0879, within(0.001));
    assertThat(greeks.theta()).isCloseTo(-0.01247, within(0.001));
    assertThat(greeks.rho()).isCloseTo(0.1398, within(0.001));
  }

  @Test
  void hullTextbookPutGreeksMatchParity() {
    // Same inputs as the call test → put Greeks via parity relations:
    //   delta_put = delta_call − e^(−qT) = 0.7791 − 1.0 = −0.2209  (q = 0)
    //   gamma_put = gamma_call = 0.0498
    //   vega_put = vega_call = 0.0879
    //   theta_put (annualised) = theta_call + r·K·e^(−rT) = −4.5532 + 0.10·40·0.9512 = −0.7484
    //              → ÷365 ≈ −0.00205 per day
    //   rho_put (annualised) = rho_call − K·T·e^(−rT) = 13.98 − 19.024 = −5.044 → −0.05044 per 1%
    var contract = new OptionContract(42.0, 40.0, 0.5, 0.20, 0.10, 0.0, OptionType.PUT);
    var greeks = calculator.compute(contract);
    assertThat(greeks.delta()).isCloseTo(-0.2209, within(0.001));
    assertThat(greeks.gamma()).isCloseTo(0.0498, within(0.001));
    assertThat(greeks.vega()).isCloseTo(0.0879, within(0.001));
    assertThat(greeks.theta()).isCloseTo(-0.00205, within(0.001));
    assertThat(greeks.rho()).isCloseTo(-0.05044, within(0.001));
  }

  @Test
  void atTheMoneyCallDeltaIsNearHalf() {
    var contract = new OptionContract(100, 100, 0.5, 0.25, 0.01, 0.0, OptionType.CALL);
    // ATM-forward delta is slightly > 0.5 because the carry pushes d1 above zero.
    assertThat(calculator.compute(contract).delta()).isCloseTo(0.55, within(0.05));
  }

  @Test
  void atTheMoneyPutDeltaIsNearMinusHalf() {
    var contract = new OptionContract(100, 100, 0.5, 0.25, 0.01, 0.0, OptionType.PUT);
    assertThat(calculator.compute(contract).delta()).isCloseTo(-0.45, within(0.05));
  }

  @Test
  void deltaInBoundsForAnyContract() {
    var deepItmCall = new OptionContract(200, 100, 0.5, 0.20, 0.03, 0.0, OptionType.CALL);
    var deepOtmCall = new OptionContract(50, 100, 0.5, 0.20, 0.03, 0.0, OptionType.CALL);
    var deepItmPut = new OptionContract(50, 100, 0.5, 0.20, 0.03, 0.0, OptionType.PUT);
    var deepOtmPut = new OptionContract(200, 100, 0.5, 0.20, 0.03, 0.0, OptionType.PUT);

    assertThat(calculator.compute(deepItmCall).delta()).isBetween(0.0, 1.0);
    assertThat(calculator.compute(deepOtmCall).delta()).isBetween(0.0, 1.0);
    assertThat(calculator.compute(deepItmPut).delta()).isBetween(-1.0, 0.0);
    assertThat(calculator.compute(deepOtmPut).delta()).isBetween(-1.0, 0.0);

    assertThat(calculator.compute(deepItmCall).delta()).isGreaterThan(0.95);
    assertThat(calculator.compute(deepOtmCall).delta()).isLessThan(0.05);
    assertThat(calculator.compute(deepItmPut).delta()).isLessThan(-0.95);
    assertThat(calculator.compute(deepOtmPut).delta()).isGreaterThan(-0.05);
  }

  @Test
  void gammaSameForCallAndPut() {
    var call = new OptionContract(100, 105, 0.5, 0.30, 0.04, 0.02, OptionType.CALL);
    var put = new OptionContract(100, 105, 0.5, 0.30, 0.04, 0.02, OptionType.PUT);
    assertThat(calculator.compute(call).gamma())
        .isCloseTo(calculator.compute(put).gamma(), within(1e-10));
  }

  @Test
  void vegaSameForCallAndPut() {
    var call = new OptionContract(100, 105, 0.5, 0.30, 0.04, 0.02, OptionType.CALL);
    var put = new OptionContract(100, 105, 0.5, 0.30, 0.04, 0.02, OptionType.PUT);
    assertThat(calculator.compute(call).vega())
        .isCloseTo(calculator.compute(put).vega(), within(1e-10));
  }

  @Test
  void gammaIsAlwaysNonNegative() {
    for (double sigma : new double[] {0.05, 0.15, 0.30, 0.80}) {
      var contract = new OptionContract(100, 100, 0.5, sigma, 0.03, 0.0, OptionType.CALL);
      assertThat(calculator.compute(contract).gamma()).isGreaterThanOrEqualTo(0.0);
    }
  }

  @Test
  void vegaIsAlwaysNonNegative() {
    var contract = new OptionContract(100, 100, 1.0, 0.25, 0.03, 0.0, OptionType.CALL);
    assertThat(calculator.compute(contract).vega()).isGreaterThan(0.0);
  }

  @Test
  void deltaMatchesNumericalBumpForCall() {
    // Numerical bump check: delta ≈ (price(S+h) - price(S-h)) / (2h)
    var base = new OptionContract(100, 100, 0.5, 0.25, 0.03, 0.0, OptionType.CALL);
    double h = 0.01;
    var up = new OptionContract(100 + h, 100, 0.5, 0.25, 0.03, 0.0, OptionType.CALL);
    var down = new OptionContract(100 - h, 100, 0.5, 0.25, 0.03, 0.0, OptionType.CALL);
    double numericDelta = (pricer.price(up) - pricer.price(down)) / (2 * h);
    assertThat(calculator.compute(base).delta()).isCloseTo(numericDelta, within(1e-4));
  }

  @Test
  void gammaMatchesNumericalSecondDerivative() {
    var base = new OptionContract(100, 100, 0.5, 0.25, 0.03, 0.0, OptionType.CALL);
    double h = 0.5;
    var up = new OptionContract(100 + h, 100, 0.5, 0.25, 0.03, 0.0, OptionType.CALL);
    var down = new OptionContract(100 - h, 100, 0.5, 0.25, 0.03, 0.0, OptionType.CALL);
    double numericGamma =
        (pricer.price(up) - 2 * pricer.price(base) + pricer.price(down)) / (h * h);
    assertThat(calculator.compute(base).gamma()).isCloseTo(numericGamma, within(1e-4));
  }

  @Test
  void vegaMatchesNumericalBump() {
    var base = new OptionContract(100, 100, 0.5, 0.25, 0.03, 0.0, OptionType.CALL);
    double h = 0.0001;
    var up = new OptionContract(100, 100, 0.5, 0.25 + h, 0.03, 0.0, OptionType.CALL);
    var down = new OptionContract(100, 100, 0.5, 0.25 - h, 0.03, 0.0, OptionType.CALL);
    double numericVegaAnnual = (pricer.price(up) - pricer.price(down)) / (2 * h);
    // Our reported vega is per-1%-vol; rescale numerical vega to compare.
    assertThat(calculator.compute(base).vega() * 100.0).isCloseTo(numericVegaAnnual, within(1e-3));
  }

  @Test
  void thetaConventionRespectsConfiguredDayCount() {
    var contract = new OptionContract(100, 100, 0.5, 0.25, 0.03, 0.0, OptionType.CALL);
    var calendar = new GreeksCalculator(new OptionsPricingConfig(365.0, 100, 1e-6, 1e-4, 5.0));
    var trading = new GreeksCalculator(new OptionsPricingConfig(252.0, 100, 1e-6, 1e-4, 5.0));
    double calendarTheta = calendar.compute(contract).theta();
    double tradingTheta = trading.compute(contract).theta();
    // Same annualised theta → trading-day theta is more negative by factor 365/252 (1.448×).
    assertThat(tradingTheta).isCloseTo(calendarTheta * (365.0 / 252.0), within(1e-6));
  }
}
