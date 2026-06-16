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

  public String marketFor(String symbol) {
    return config.marketFor(symbol);
  }

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
