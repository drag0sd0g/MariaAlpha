package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Fill;
import com.mariaalpha.executionengine.model.RiskAlert;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.publisher.RiskAlertPublisher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tracks realized intraday P&L and trips the kill-switch when the configured {@code maxDailyLoss}
 * is breached.
 *
 * <p>Realized P&L requires knowing the entry price of the position being closed, so the monitor
 * keeps its own per-symbol (net quantity, average cost) book, updated on every fill. A fill that
 * extends a position realizes nothing; a fill that reduces or flips one realizes {@code (fillPrice
 * − avgCost) × closedQty × sign(position)} on the closed portion.
 */
@Component
public class DailyLossMonitor {

  private static final Logger LOG = LoggerFactory.getLogger(DailyLossMonitor.class);
  private static final ZoneId ET = ZoneId.of("America/New_York");
  private static final int SCALE = 8;

  /** Per-symbol open position: signed share quantity and average entry cost. */
  private record Lot(BigDecimal quantity, BigDecimal avgCost) {
    static final Lot FLAT = new Lot(BigDecimal.ZERO, BigDecimal.ZERO);
  }

  private final RiskLimitsConfig config;
  private final RiskAlertPublisher alertPublisher;
  private final AtomicReference<BigDecimal> dailyPnl = new AtomicReference<>(BigDecimal.ZERO);
  private final AtomicBoolean tradingHalted = new AtomicBoolean(false);
  private final ConcurrentHashMap<String, Lot> lots = new ConcurrentHashMap<>();
  private volatile LocalDate lastResetDate;

  public DailyLossMonitor(RiskLimitsConfig config, RiskAlertPublisher alertPublisher) {
    this.config = config;
    this.alertPublisher = alertPublisher;
    this.lastResetDate = LocalDate.now(ET);
  }

  /** Called on every fill: updates the per-symbol book and accumulates realized P&L. */
  public void onFill(Fill fill) {
    var realized = new AtomicReference<>(BigDecimal.ZERO);
    var fillQty = BigDecimal.valueOf(fill.fillQuantity());
    var signedQty = fill.side() == Side.BUY ? fillQty : fillQty.negate();

    lots.compute(
        fill.symbol(),
        (symbol, lot) -> {
          var current = lot == null ? Lot.FLAT : lot;
          int currentSign = current.quantity().signum();
          int fillSign = signedQty.signum();

          if (currentSign == 0 || currentSign == fillSign) {
            // Opening or extending: weighted-average the entry cost, realize nothing.
            var newQty = current.quantity().add(signedQty);
            var newAvg =
                newQty.signum() == 0
                    ? BigDecimal.ZERO
                    : current
                        .avgCost()
                        .multiply(current.quantity().abs())
                        .add(fill.fillPrice().multiply(fillQty))
                        .divide(newQty.abs(), SCALE, RoundingMode.HALF_UP);
            return new Lot(newQty, newAvg);
          }

          // Reducing or flipping: realize P&L on the closed portion.
          var closedQty = signedQty.abs().min(current.quantity().abs());
          realized.set(
              fill.fillPrice()
                  .subtract(current.avgCost())
                  .multiply(BigDecimal.valueOf(currentSign))
                  .multiply(closedQty));
          var newQty = current.quantity().add(signedQty);
          if (newQty.signum() == 0) {
            return Lot.FLAT;
          }
          // Flipped: the residual opens a new position at the fill price.
          var newAvg = newQty.signum() == currentSign ? current.avgCost() : fill.fillPrice();
          return new Lot(newQty, newAvg);
        });

    if (realized.get().signum() != 0) {
      dailyPnl.accumulateAndGet(realized.get(), BigDecimal::add);
    }

    if (!tradingHalted.get()
        && dailyPnl.get().negate().compareTo(BigDecimal.valueOf(config.maxDailyLoss())) > 0) {
      haltTrading(fill.symbol());
    }
  }

  public BigDecimal getDailyPnl() {
    return dailyPnl.get();
  }

  /** Auto-reset at market open each trading day. Open positions carry over. */
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
