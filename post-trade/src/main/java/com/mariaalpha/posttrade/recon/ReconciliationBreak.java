package com.mariaalpha.posttrade.recon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Comparator output — one record per detected discrepancy. The orchestrator persists these as
 * {@link com.mariaalpha.posttrade.entity.ReconciliationBreakEntity} rows and publishes one {@code
 * RECON_BREAK} alert per row to {@code analytics.risk-alerts}.
 */
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
    /** External venue reported a fill that has no internal counterpart. */
    MISSING_FILL,
    /** Internal records contain a fill that the external venue did not report. */
    EXTRA_FILL,
    /** Both sides report a fill for the same order but quantities differ beyond tolerance. */
    QUANTITY_MISMATCH,
    /** Both sides report a fill for the same order but average prices differ beyond tolerance. */
    PRICE_MISMATCH
  }

  public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
  }
}
