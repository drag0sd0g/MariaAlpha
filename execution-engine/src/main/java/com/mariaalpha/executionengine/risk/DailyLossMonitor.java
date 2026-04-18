package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Fill;
import com.mariaalpha.executionengine.model.RiskAlert;
import com.mariaalpha.executionengine.publisher.RiskAlertPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyLossMonitor {

  private static final Logger LOG = LoggerFactory.getLogger(DailyLossMonitor.class);
  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final RiskLimitsConfig config;
  private final RiskAlertPublisher alertPublisher;
  private final AtomicReference<BigDecimal> dailyPnl = new AtomicReference<>(BigDecimal.ZERO);
  private final AtomicBoolean tradingHalted = new AtomicBoolean(false);
  private volatile LocalDate lastResetDate;

  public DailyLossMonitor(RiskLimitsConfig config, RiskAlertPublisher alertPublisher) {
    this.config = config;
    this.alertPublisher = alertPublisher;
    this.lastResetDate = LocalDate.now(ET);
  }

  /** Called on every fill to update daily P&L. */
  public void onFill(Fill fill, BigDecimal entryPrice, String symbol) {
    // realized P&L per fill:
    // BUY fill: -(fillPrice - entryPrice) * fillQty  (unrealized until sold)
    // SELL fill: (fillPrice - entryPrice) * fillQty
    // Simplified: just track realized P&L from closing fills
    var pnl =
        fill.fillPrice().subtract(entryPrice).multiply(BigDecimal.valueOf(fill.fillQuantity()));
    dailyPnl.accumulateAndGet(pnl, BigDecimal::add);

    if (!tradingHalted.get()
        && dailyPnl.get().negate().compareTo(BigDecimal.valueOf(config.maxDailyLoss())) > 0) {
      haltTrading(symbol);
    }
  }

  public BigDecimal getDailyPnl() {
    return dailyPnl.get();
  }

  /** Auto-reset at market open each trading day. */
  @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "America/New_York")
  public void resetDailyLimits() {
    dailyPnl.set(BigDecimal.ZERO);
    tradingHalted.set(false);
    lastResetDate = LocalDate.now(ET);
    LOG.info("Daily loss monitor reset for new trading day: {}", lastResetDate);
  }

  public void resume() {
    tradingHalted.set(false);
    LOG.info("Trading resumed manually. Daily P&L: {}", getDailyPnl());
  }

  public boolean isTradingHalted() {
    return tradingHalted.get();
  }

  private void haltTrading(String symbol) {
    tradingHalted.set(true);
    LOG.error(
        "TRADING HALTED - daily loss ${} exceeds limit of ${}",
        dailyPnl.get().negate(),
        config.maxDailyLoss());
    var alert =
        new RiskAlert(
            symbol,
            "DAILY_LOSS_LIMIT_BREACH",
            "CRITICAL",
            String.format(
                "Daily P&L $%s exceeds loss limit of $%d", dailyPnl.get(), config.maxDailyLoss()),
            Instant.now());
    alertPublisher.publish(alert);
    // Cancel all open orders (handled by OrderExecutionService)
  }
}
