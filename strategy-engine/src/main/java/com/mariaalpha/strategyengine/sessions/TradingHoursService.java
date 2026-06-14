package com.mariaalpha.strategyengine.sessions;

import com.mariaalpha.strategyengine.sessions.TradingHoursConfig.MarketSchedule;
import com.mariaalpha.strategyengine.sessions.TradingHoursConfig.SessionWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves whether a symbol's market is currently trading at a given timestamp (roadmap 3.1.3).
 *
 * <p>Used by {@code StrategyEvaluationService} as an early-exit gate: ticks that arrive outside the
 * resolved market's session windows are dropped before the strategy sees them. Keeping after-hours
 * ticks out of strategy state matters most for indicator-driven strategies (Momentum's EMAs / RSI
 * would otherwise drift on stale quotes).
 *
 * <p>When {@code enabled=false} the service short-circuits to "always open" so the pre-3.1.3
 * behaviour is preserved with zero config change. Single-market deployments only need to ensure
 * {@code default-market}'s schedule is configured.
 */
@Service
public class TradingHoursService {

  private static final Logger LOG = LoggerFactory.getLogger(TradingHoursService.class);

  private final TradingHoursConfig config;

  public TradingHoursService(TradingHoursConfig config) {
    this.config = config;
    if (config.enabled()) {
      LOG.info(
          "TradingHoursService enabled with markets {} (default={})",
          config.markets().keySet(),
          config.defaultMarket());
    }
  }

  /**
   * Returns whether {@code symbol}'s market is open at {@code timestamp}. Always returns {@code
   * true} when the gate is disabled. Falls back to {@code true} when the resolved market is not
   * configured — refusing to gate beats silently dropping every tick due to a missing entry.
   */
  public boolean isMarketOpen(String symbol, Instant timestamp) {
    if (!config.enabled()) {
      return true;
    }
    return scheduleFor(symbol)
        .map(schedule -> isOpenIn(schedule, timestamp))
        .orElseGet(
            () -> {
              LOG.warn(
                  "No schedule configured for market {} (symbol {}); allowing tick through",
                  config.marketFor(symbol),
                  symbol);
              return true;
            });
  }

  /** Returns the market name a symbol resolves to. */
  public String marketFor(String symbol) {
    return config.marketFor(symbol);
  }

  /**
   * Looks up the {@link MarketSchedule} for a symbol. Useful for diagnostics endpoints and the
   * forthcoming `/api/strategies/state` integration that surfaces market status alongside strategy
   * status.
   */
  public Optional<MarketSchedule> scheduleFor(String symbol) {
    var name = config.marketFor(symbol);
    return Optional.ofNullable(config.markets().get(name));
  }

  private static boolean isOpenIn(MarketSchedule schedule, Instant timestamp) {
    ZonedDateTime local = timestamp.atZone(schedule.timezone());
    LocalDate date = local.toLocalDate();
    if (!schedule.tradingDays().contains(local.getDayOfWeek())) {
      return false;
    }
    if (schedule.holidays().contains(date)) {
      return false;
    }
    LocalTime time = local.toLocalTime();
    for (SessionWindow window : schedule.sessions()) {
      if (window.contains(time)) {
        return true;
      }
    }
    return false;
  }
}
