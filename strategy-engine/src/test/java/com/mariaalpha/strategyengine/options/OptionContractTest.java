package com.mariaalpha.strategyengine.options;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OptionContractTest {

  @Test
  void rejectsNonPositiveSpot() {
    assertThatThrownBy(() -> new OptionContract(0.0, 100, 0.5, 0.2, 0.05, 0.0, OptionType.CALL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spot");
    assertThatThrownBy(() -> new OptionContract(-1.0, 100, 0.5, 0.2, 0.05, 0.0, OptionType.CALL))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonPositiveStrike() {
    assertThatThrownBy(() -> new OptionContract(100, 0.0, 0.5, 0.2, 0.05, 0.0, OptionType.PUT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strike");
  }

  @Test
  void rejectsNonPositiveTimeToExpiry() {
    assertThatThrownBy(() -> new OptionContract(100, 100, 0.0, 0.2, 0.05, 0.0, OptionType.CALL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeToExpiryYears");
  }

  @Test
  void rejectsNonPositiveVolatility() {
    assertThatThrownBy(() -> new OptionContract(100, 100, 0.5, 0.0, 0.05, 0.0, OptionType.CALL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("volatility");
  }

  @Test
  void rejectsNegativeDividendYield() {
    assertThatThrownBy(() -> new OptionContract(100, 100, 0.5, 0.2, 0.05, -0.01, OptionType.CALL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dividendYield");
  }

  @Test
  void rejectsNullType() {
    assertThatThrownBy(() -> new OptionContract(100, 100, 0.5, 0.2, 0.05, 0.0, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsNegativeRiskFreeRate() {
    new OptionContract(100, 100, 0.5, 0.2, -0.005, 0.0, OptionType.CALL);
  }
}
