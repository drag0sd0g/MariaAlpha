package com.mariaalpha.executionengine.health;

import com.mariaalpha.executionengine.risk.DailyLossMonitor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class TradingHaltHealthIndicator implements HealthIndicator {

  private final DailyLossMonitor monitor;

  public TradingHaltHealthIndicator(DailyLossMonitor monitor) {
    this.monitor = monitor;
  }

  @Override
  public Health health() {
    if (monitor.isTradingHalted()) {
      return Health.status("WARN")
          .withDetail("tradingHalted", true)
          .withDetail("dailyPnl", monitor.getDailyPnl().toPlainString())
          .withDetail("reason", "Daily loss limit breached")
          .build();
    }
    return Health.up()
        .withDetail("tradingHalted", false)
        .withDetail("dailyPnl", monitor.getDailyPnl().toPlainString())
        .build();
  }
}
