package com.mariaalpha.ordermanager.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "order-manager.currency")
public record CurrencyConfig(
    String defaultCurrency, Map<String, String> overrides, List<String> known) {

  @ConstructorBinding
  public CurrencyConfig {
    defaultCurrency = normalise(defaultCurrency, "USD");
    overrides = normaliseMap(overrides);
    known = normaliseList(known);
  }

  public String currencyFor(String symbol) {
    if (symbol == null) {
      return defaultCurrency;
    }
    return overrides.getOrDefault(symbol.toUpperCase(Locale.ROOT), defaultCurrency);
  }

  private static String normalise(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static Map<String, String> normaliseMap(Map<String, String> raw) {
    if (raw == null) {
      return Map.of();
    }
    var copy = new java.util.HashMap<String, String>(raw.size());
    raw.forEach(
        (k, v) -> {
          if (k != null && v != null && !v.isBlank()) {
            copy.put(k.trim().toUpperCase(Locale.ROOT), v.trim().toUpperCase(Locale.ROOT));
          }
        });
    return Map.copyOf(copy);
  }

  private static List<String> normaliseList(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of("USD");
    }
    return raw.stream()
        .filter(s -> s != null && !s.isBlank())
        .map(s -> s.trim().toUpperCase(Locale.ROOT))
        .distinct()
        .toList();
  }
}
