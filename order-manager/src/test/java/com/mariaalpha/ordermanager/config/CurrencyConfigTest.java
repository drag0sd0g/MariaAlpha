package com.mariaalpha.ordermanager.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CurrencyConfigTest {

  @Test
  void currencyForReturnsOverrideWhenSymbolPresent() {
    var config =
        new CurrencyConfig(
            "USD", Map.of("7203", "JPY", "SAP", "EUR"), List.of("USD", "JPY", "EUR"));
    assertThat(config.currencyFor("7203")).isEqualTo("JPY");
    assertThat(config.currencyFor("SAP")).isEqualTo("EUR");
  }

  @Test
  void currencyForFallsBackToDefaultWhenSymbolUnknown() {
    var config = new CurrencyConfig("USD", Map.of("7203", "JPY"), List.of("USD", "JPY"));
    assertThat(config.currencyFor("AAPL")).isEqualTo("USD");
  }

  @Test
  void currencyForNormalisesSymbolCase() {
    var config = new CurrencyConfig("USD", Map.of("SAP", "EUR"), List.of("USD", "EUR"));
    assertThat(config.currencyFor("sap")).isEqualTo("EUR");
  }

  @Test
  void currencyForHandlesNullSymbol() {
    var config = new CurrencyConfig("USD", Map.of(), List.of("USD"));
    assertThat(config.currencyFor(null)).isEqualTo("USD");
  }

  @Test
  void defaultsToUsdWhenDefaultCurrencyMissing() {
    var config = new CurrencyConfig(null, Map.of(), List.of());
    assertThat(config.defaultCurrency()).isEqualTo("USD");
    assertThat(config.known()).containsExactly("USD");
  }

  @Test
  void overrideValuesAndKeysAreUppercased() {
    var config = new CurrencyConfig("usd", Map.of("sap", "eur"), List.of("usd", "eur"));
    assertThat(config.defaultCurrency()).isEqualTo("USD");
    assertThat(config.overrides()).containsEntry("SAP", "EUR");
    assertThat(config.known()).containsExactly("USD", "EUR");
  }

  @Test
  void blankOverrideValuesAreDropped() {
    var raw = new java.util.HashMap<String, String>();
    raw.put("AAPL", "");
    raw.put("SAP", "EUR");
    var config = new CurrencyConfig("USD", raw, List.of("USD", "EUR"));
    assertThat(config.overrides()).doesNotContainKey("AAPL").containsEntry("SAP", "EUR");
  }
}
