package com.mariaalpha.posttrade.recon;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
    EXTERNAL,
    MIRROR
  }

  public enum TargetDate {
    TODAY,
    PREVIOUS_TRADING_DAY
  }

  public record Alpaca(
      String baseUrl, String apiKey, String apiSecret, long httpTimeoutMs, String activityTypes) {}
}
