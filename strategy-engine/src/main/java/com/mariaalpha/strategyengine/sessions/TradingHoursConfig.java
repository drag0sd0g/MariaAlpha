package com.mariaalpha.strategyengine.sessions;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Per-market trading-session schedule (roadmap 3.1.3 — multi-market trading hours).
 *
 * <p>A {@link MarketSchedule} carries a timezone (so each session's open / close are in local
 * time), a list of {@link SessionWindow}s (TSE has two — the morning Zenba and afternoon Goba, US
 * markets have one continuous session, LSE has one), a set of trading days, and an optional
 * holiday list.
 *
 * <p>Symbols pick their market via {@link #symbolOverrides()}; symbols not listed fall back to
 * {@link #defaultMarket()}. The {@link #enabled()} flag is a soft feature gate — when {@code
 * false} the service treats every market as 24/7 open, matching the pre-3.1.3 behaviour.
 *
 * <p>FX conversion is unrelated and lives separately (see {@code order-manager.currency} +
 * {@code CurrencyExposureService}).
 */
@ConfigurationProperties(prefix = "strategy-engine.trading-hours")
public record TradingHoursConfig(
    boolean enabled,
    String defaultMarket,
    Map<String, MarketSchedule> markets,
    Map<String, String> symbolOverrides) {

  @ConstructorBinding
  public TradingHoursConfig {
    defaultMarket = defaultMarket == null ? "NYSE" : defaultMarket.trim().toUpperCase(Locale.ROOT);
    markets = markets == null ? Map.of() : Map.copyOf(markets);
    symbolOverrides = normaliseOverrides(symbolOverrides);
  }

  /** Resolves the market name a symbol belongs to (override first, then default). */
  public String marketFor(String symbol) {
    if (symbol == null) {
      return defaultMarket;
    }
    return symbolOverrides.getOrDefault(symbol.toUpperCase(Locale.ROOT), defaultMarket);
  }

  private static Map<String, String> normaliseOverrides(Map<String, String> raw) {
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

  /** A single market's session calendar. */
  public record MarketSchedule(
      ZoneId timezone,
      List<SessionWindow> sessions,
      Set<DayOfWeek> tradingDays,
      Set<LocalDate> holidays) {

    public MarketSchedule {
      if (timezone == null) {
        throw new IllegalArgumentException("timezone is required");
      }
      sessions = sessions == null ? List.of() : List.copyOf(sessions);
      tradingDays =
          tradingDays == null
              ? Set.of(
                  DayOfWeek.MONDAY,
                  DayOfWeek.TUESDAY,
                  DayOfWeek.WEDNESDAY,
                  DayOfWeek.THURSDAY,
                  DayOfWeek.FRIDAY)
              : Set.copyOf(tradingDays);
      holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
    }
  }

  /**
   * One contiguous open-to-close window in the market's local time. TSE has two (Zenba / Goba),
   * NYSE / NASDAQ / LSE have one; some venues (after-hours / extended trading) would have a third.
   * Inclusive open, exclusive close — matches the convention every exchange API uses.
   */
  public record SessionWindow(LocalTime open, LocalTime close) {
    public SessionWindow {
      if (open == null || close == null) {
        throw new IllegalArgumentException("open and close are required");
      }
      if (!close.isAfter(open)) {
        throw new IllegalArgumentException("close must be after open: " + open + " → " + close);
      }
    }

    public boolean contains(LocalTime t) {
      return !t.isBefore(open) && t.isBefore(close);
    }
  }
}
