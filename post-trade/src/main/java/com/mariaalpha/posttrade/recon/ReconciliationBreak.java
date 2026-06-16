package com.mariaalpha.posttrade.recon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ReconciliationBreak(
    LocalDate reconDate,
    UUID orderId,
    BreakType breakType,
    Severity severity,
    String symbol,
    String description,
    BigDecimal internalQty,
    BigDecimal externalQty,
    BigDecimal internalPrice,
    BigDecimal externalPrice,
    BigDecimal notional) {

  public enum BreakType {
    MISSING_FILL,
    EXTRA_FILL,
    QUANTITY_MISMATCH,
    PRICE_MISMATCH
  }

  public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
  }
}
