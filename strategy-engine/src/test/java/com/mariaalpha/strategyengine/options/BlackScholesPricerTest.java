package com.mariaalpha.strategyengine.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class BlackScholesPricerTest {

  private final BlackScholesPricer pricer = new BlackScholesPricer();

  @Test
  void hullTextbookCallPutValues() {
    var call = new OptionContract(42.0, 40.0, 0.5, 0.20, 0.10, 0.0, OptionType.CALL);
    var put = new OptionContract(42.0, 40.0, 0.5, 0.20, 0.10, 0.0, OptionType.PUT);
    assertThat(pricer.price(call)).isCloseTo(4.76, within(0.01));
    assertThat(pricer.price(put)).isCloseTo(0.81, within(0.01));
  }

  @Test
  void putCallParityHoldsWithDividendYield() {
    double spot = 100;
    double strike = 95;
    double t = 1.25;
    double sigma = 0.30;
    double r = 0.04;
    double q = 0.02;
    var call = new OptionContract(spot, strike, t, sigma, r, q, OptionType.CALL);
    var put = new OptionContract(spot, strike, t, sigma, r, q, OptionType.PUT);
    double parityRhs = spot * Math.exp(-q * t) - strike * Math.exp(-r * t);
    assertThat(pricer.price(call) - pricer.price(put)).isCloseTo(parityRhs, within(1e-8));
  }

  @Test
  void deepInTheMoneyCallApproachesIntrinsicValue() {
    var contract = new OptionContract(200, 100, 0.05, 0.10, 0.03, 0.0, OptionType.CALL);
    double intrinsicForward =
        contract.spot() * Math.exp(-contract.dividendYield() * contract.timeToExpiryYears())
            - contract.strike() * Math.exp(-contract.riskFreeRate() * contract.timeToExpiryYears());
    assertThat(pricer.price(contract)).isCloseTo(intrinsicForward, within(0.01));
  }

  @Test
  void deepOutOfTheMoneyPutApproachesZero() {
    var contract = new OptionContract(200, 100, 0.05, 0.10, 0.03, 0.0, OptionType.PUT);
    assertThat(pricer.price(contract)).isCloseTo(0.0, within(1e-3));
  }

  @Test
  void deepInTheMoneyPutApproachesIntrinsicValue() {
    var contract = new OptionContract(50, 200, 0.05, 0.10, 0.03, 0.0, OptionType.PUT);
    double intrinsicForward =
        contract.strike() * Math.exp(-contract.riskFreeRate() * contract.timeToExpiryYears())
            - contract.spot() * Math.exp(-contract.dividendYield() * contract.timeToExpiryYears());
    assertThat(pricer.price(contract)).isCloseTo(intrinsicForward, within(0.01));
  }

  @Test
  void atTheMoneyCallEqualsAtTheMoneyPutAtZeroCarry() {
    var call = new OptionContract(100, 100, 1.0, 0.25, 0.02, 0.02, OptionType.CALL);
    var put = new OptionContract(100, 100, 1.0, 0.25, 0.02, 0.02, OptionType.PUT);
    assertThat(pricer.price(call)).isCloseTo(pricer.price(put), within(1e-8));
  }

  @Test
  void higherVolatilityIncreasesPremium() {
    var lowVol = new OptionContract(100, 100, 0.5, 0.15, 0.03, 0.0, OptionType.CALL);
    var highVol = new OptionContract(100, 100, 0.5, 0.40, 0.03, 0.0, OptionType.CALL);
    assertThat(pricer.price(highVol)).isGreaterThan(pricer.price(lowVol));
  }

  @Test
  void longerTimeIncreasesAtmCallPremium() {
    var nearTerm = new OptionContract(100, 100, 0.1, 0.20, 0.05, 0.0, OptionType.CALL);
    var farTerm = new OptionContract(100, 100, 2.0, 0.20, 0.05, 0.0, OptionType.CALL);
    assertThat(pricer.price(farTerm)).isGreaterThan(pricer.price(nearTerm));
  }

  @Test
  void higherDividendYieldDecreasesCallPremium() {
    var noDiv = new OptionContract(100, 100, 1.0, 0.25, 0.05, 0.0, OptionType.CALL);
    var withDiv = new OptionContract(100, 100, 1.0, 0.25, 0.05, 0.04, OptionType.CALL);
    assertThat(pricer.price(withDiv)).isLessThan(pricer.price(noDiv));
  }

  @Test
  void higherDividendYieldIncreasesPutPremium() {
    var noDiv = new OptionContract(100, 100, 1.0, 0.25, 0.05, 0.0, OptionType.PUT);
    var withDiv = new OptionContract(100, 100, 1.0, 0.25, 0.05, 0.04, OptionType.PUT);
    assertThat(pricer.price(withDiv)).isGreaterThan(pricer.price(noDiv));
  }
}
