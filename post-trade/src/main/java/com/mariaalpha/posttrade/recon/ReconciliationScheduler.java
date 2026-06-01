package com.mariaalpha.posttrade.recon;

import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Source;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires {@link EodReconciliationService#runForDate} on the cron defined in {@code
 * post-trade.recon.cron}. Disabled by default unless {@code post-trade.recon.enabled=true}; the
 * docker-compose stack runs with it on (in MIRROR mode), production deployments turn it on with
 * EXTERNAL mode + a 22:00 ET cron.
 */
@Component
@ConditionalOnProperty(prefix = "post-trade.recon", name = "enabled", havingValue = "true")
public class ReconciliationScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(ReconciliationScheduler.class);

  private final EodReconciliationService service;
  private final ReconConfig config;

  public ReconciliationScheduler(EodReconciliationService service, ReconConfig config) {
    this.service = service;
    this.config = config;
  }

  @Scheduled(cron = "${post-trade.recon.cron}", zone = "${post-trade.recon.schedule-zone}")
  public void runScheduled() {
    LocalDate date = resolveTargetDate();
    LOG.info("Scheduled EOD reconciliation firing for {} (mode={})", date, config.mode());
    service.runForDate(date, Source.SCHEDULED);
  }

  LocalDate resolveTargetDate() {
    ZonedDateTime now = ZonedDateTime.now(ZoneId.of(config.scheduleZone()));
    return switch (config.targetDate()) {
      case TODAY -> now.toLocalDate();
      case PREVIOUS_TRADING_DAY -> previousTradingDay(now.toLocalDate());
    };
  }

  static LocalDate previousTradingDay(LocalDate from) {
    LocalDate prev = from.minusDays(1);
    while (prev.getDayOfWeek() == DayOfWeek.SATURDAY || prev.getDayOfWeek() == DayOfWeek.SUNDAY) {
      prev = prev.minusDays(1);
    }
    return prev;
  }
}
