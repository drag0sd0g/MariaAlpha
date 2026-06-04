package com.mariaalpha.posttrade.recon;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the EOD reconciliation engine.
 *
 * <p>{@link Mode#EXTERNAL} pulls the day's activity report from Alpaca and compares against
 * internal fills. {@link Mode#MIRROR} is the simulated-stack mode — every internal fill is treated
 * as if it had been confirmed by the external venue, so the comparator path runs end-to-end and
 * always produces zero breaks. Switching modes never changes the engine's logic, only the source
 * the external fills are drawn from.
 */
@ConfigurationProperties(prefix = "post-trade.recon")
public record ReconConfig(
    boolean enabled,
    Mode mode,
    String cron,
    String scheduleZone,
    TargetDate targetDate,
    BigDecimal priceToleranceBps,
    BigDecimal quantityTolerance,
    BigDecimal highSeverityNotional,
    BigDecimal criticalSeverityNotional,
    Alpaca alpaca) {

  public enum Mode {
    /** Pull fills from the configured exchange (Alpaca) and compare against internal records. */
    EXTERNAL,
    /** Mirror internal fills as external — for simulated / dev stacks. */
    MIRROR
  }

  public enum TargetDate {
    TODAY,
    PREVIOUS_TRADING_DAY
  }

  public record Alpaca(
      String baseUrl, String apiKey, String apiSecret, long httpTimeoutMs, String activityTypes) {}
}
