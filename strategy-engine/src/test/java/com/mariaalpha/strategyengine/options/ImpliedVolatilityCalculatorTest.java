package com.mariaalpha.strategyengine.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ImpliedVolatilityCalculatorTest {

  private static final OptionsPricingConfig CONFIG =
      new OptionsPricingConfig(365.0, 100, 1.0e-6, 1.0e-4, 5.0);

  private final BlackScholesPricer pricer = new BlackScholesPricer();
  private final ImpliedVolatilityCalculator solver =
      new ImpliedVolatilityCalculator(pricer, CONFIG);

  @Test
  void roundTripsForwardPriceCallVolatility() {
    double trueVol = 0.27;
    var contract = new OptionContract(100, 100, 0.5, trueVol, 0.03, 0.0, OptionType.CALL);
    double marketPrice = pricer.price(contract);

    var result = solver.solve(100, 100, 0.5, 0.03, 0.0, OptionType.CALL, marketPrice);
    assertThat(result.impliedVolatility()).isCloseTo(trueVol, within(1e-5));
    assertThat(Math.abs(result.residual())).isLessThanOrEqualTo(CONFIG.impliedVolTolerance());
  }

  @Test
  void roundTripsForwardPricePutVolatility() {
    double trueVol = 0.45;
    var contract = new OptionContract(50, 60, 0.25, trueVol, 0.02, 0.01, OptionType.PUT);
    double marketPrice = pricer.price(contract);
    var result = solver.solve(50, 60, 0.25, 0.02, 0.01, OptionType.PUT, marketPrice);
    assertThat(result.impliedVolatility()).isCloseTo(trueVol, within(1e-5));
  }

  @Test
  void convergesQuicklyForReasonableInputs() {
    var contract = new OptionContract(100, 100, 0.5, 0.30, 0.03, 0.0, OptionType.CALL);
    double marketPrice = pricer.price(contract);
    var result = solver.solve(100, 100, 0.5, 0.03, 0.0, OptionType.CALL, marketPrice);
    assertThat(result.method()).isEqualTo(ImpliedVolatilityCalculator.Method.NEWTON);
    assertThat(result.iterations()).isLessThan(10);
  }

  @Test
  void rejectsPriceBelowIntrinsic() {
    assertThatThrownBy(() -> solver.solve(150, 100, 1.0, 0.05, 0.0, OptionType.CALL, 30.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no-arbitrage band");
  }

  @Test
  void rejectsPriceAboveForwardSpot() {
    assertThatThrownBy(() -> solver.solve(100, 100, 1.0, 0.05, 0.0, OptionType.CALL, 105.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no-arbitrage band");
  }

  @Test
  void rejectsNonPositiveMarketPrice() {
    assertThatThrownBy(() -> solver.solve(100, 100, 1.0, 0.05, 0.0, OptionType.CALL, 0.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> solver.solve(100, 100, 1.0, 0.05, 0.0, OptionType.CALL, -1.0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
