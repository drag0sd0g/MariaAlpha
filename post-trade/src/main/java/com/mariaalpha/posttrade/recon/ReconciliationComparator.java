package com.mariaalpha.posttrade.recon;

import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.recon.ReconciliationBreak.BreakType;
import com.mariaalpha.posttrade.recon.ReconciliationBreak.Severity;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReconciliationComparator {

  private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

  private final BigDecimal priceToleranceBps;
  private final BigDecimal quantityTolerance;
  private final BigDecimal highSeverityNotional;
  private final BigDecimal criticalSeverityNotional;

  public ReconciliationComparator(
      BigDecimal priceToleranceBps,
      BigDecimal quantityTolerance,
      BigDecimal highSeverityNotional,
      BigDecimal criticalSeverityNotional) {
    this.priceToleranceBps = priceToleranceBps;
    this.quantityTolerance = quantityTolerance;
    this.highSeverityNotional = highSeverityNotional;
    this.criticalSeverityNotional = criticalSeverityNotional;
  }

  public List<ReconciliationBreak> compare(
      LocalDate reconDate, List<InternalFill> internal, List<ExternalFill> external) {
    Map<String, Aggregate> internalByKey = aggregate(internal);
    Map<String, Aggregate> externalByKey = aggregateExternal(external);

    List<ReconciliationBreak> breaks = new ArrayList<>();

    for (var entry : internalByKey.entrySet()) {
      if (!externalByKey.containsKey(entry.getKey())) {
        Aggregate a = entry.getValue();
        BigDecimal notional = nullSafeMultiply(a.qty, a.vwap);
        breaks.add(
            new ReconciliationBreak(
                reconDate,
                a.orderId,
                BreakType.EXTRA_FILL,
                severityForNotional(notional),
                a.symbol,
                "Internal reports a fill for "
                    + a.symbol
                    + " ("
                    + a.qty
                    + " @ "
                    + a.vwap
                    + ") with no matching external activity",
                a.qty,
                null,
                a.vwap,
                null,
                notional));
      }
    }

    for (var entry : externalByKey.entrySet()) {
      if (!internalByKey.containsKey(entry.getKey())) {
        Aggregate a = entry.getValue();
        BigDecimal notional = nullSafeMultiply(a.qty, a.vwap);
        breaks.add(
            new ReconciliationBreak(
                reconDate,
                a.orderId,
                BreakType.MISSING_FILL,
                severityForNotional(notional),
                a.symbol,
                "External activity for "
                    + a.symbol
                    + " ("
                    + a.qty
                    + " @ "
                    + a.vwap
                    + ") has no matching internal fill",
                null,
                a.qty,
                null,
                a.vwap,
                notional));
      }
    }

    for (var entry : internalByKey.entrySet()) {
      Aggregate ext = externalByKey.get(entry.getKey());
      if (ext == null) {
        continue;
      }
      Aggregate intl = entry.getValue();
      BigDecimal qtyDiff = intl.qty.subtract(ext.qty).abs();
      if (qtyDiff.compareTo(quantityTolerance) > 0) {
        BigDecimal notional = nullSafeMultiply(qtyDiff, intl.vwap);
        breaks.add(
            new ReconciliationBreak(
                reconDate,
                intl.orderId,
                BreakType.QUANTITY_MISMATCH,
                severityForNotional(notional),
                intl.symbol,
                "Quantity mismatch on "
                    + intl.symbol
                    + " (internal="
                    + intl.qty
                    + ", external="
                    + ext.qty
                    + ")",
                intl.qty,
                ext.qty,
                intl.vwap,
                ext.vwap,
                notional));
      }

      BigDecimal priceDiffBps = relativeDiffBps(intl.vwap, ext.vwap);
      if (priceDiffBps.compareTo(priceToleranceBps) > 0) {
        BigDecimal notional = nullSafeMultiply(intl.qty, intl.vwap);
        breaks.add(
            new ReconciliationBreak(
                reconDate,
                intl.orderId,
                BreakType.PRICE_MISMATCH,
                severityForPriceDiff(priceDiffBps),
                intl.symbol,
                "Price mismatch on "
                    + intl.symbol
                    + " (internal="
                    + intl.vwap
                    + ", external="
                    + ext.vwap
                    + ", diff="
                    + priceDiffBps.setScale(2, RoundingMode.HALF_UP)
                    + "bps)",
                intl.qty,
                ext.qty,
                intl.vwap,
                ext.vwap,
                notional));
      }
    }

    return breaks;
  }

  private static Map<String, Aggregate> aggregate(List<InternalFill> fills) {
    Map<String, Aggregate> map = new LinkedHashMap<>();
    for (InternalFill f : fills) {
      String key = matchKey(f.exchangeOrderId(), f.clientOrderId());
      if (key == null) {
        continue;
      }
      Aggregate agg =
          map.computeIfAbsent(
              key,
              k ->
                  new Aggregate(
                      f.orderId(), f.symbol(), f.side(), BigDecimal.ZERO, BigDecimal.ZERO));
      agg.add(f.quantity(), f.price());
    }
    finalizeAll(map);
    return map;
  }

  private static Map<String, Aggregate> aggregateExternal(List<ExternalFill> fills) {
    Map<String, Aggregate> map = new LinkedHashMap<>();
    for (ExternalFill f : fills) {
      String key = matchKey(f.exchangeOrderId(), f.clientOrderId());
      if (key == null) {
        continue;
      }
      UUID orderId = parseClientOrderId(f.clientOrderId());
      Aggregate agg =
          map.computeIfAbsent(
              key,
              k -> new Aggregate(orderId, f.symbol(), f.side(), BigDecimal.ZERO, BigDecimal.ZERO));
      agg.add(f.quantity(), f.price());
    }
    finalizeAll(map);
    return map;
  }

  private static void finalizeAll(Map<String, Aggregate> map) {
    for (Aggregate a : map.values()) {
      a.finishVwap();
    }
  }

  static String matchKey(String exchangeOrderId, String clientOrderId) {
    if (exchangeOrderId != null && !exchangeOrderId.isBlank()) {
      return "X:" + exchangeOrderId;
    }
    if (clientOrderId != null && !clientOrderId.isBlank()) {
      return "C:" + clientOrderId;
    }
    return null;
  }

  private static UUID parseClientOrderId(String s) {
    if (s == null) {
      return null;
    }
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private Severity severityForNotional(BigDecimal notional) {
    if (notional == null) {
      return Severity.LOW;
    }
    BigDecimal abs = notional.abs();
    if (criticalSeverityNotional != null && abs.compareTo(criticalSeverityNotional) >= 0) {
      return Severity.CRITICAL;
    }
    if (highSeverityNotional != null && abs.compareTo(highSeverityNotional) >= 0) {
      return Severity.HIGH;
    }
    if (abs.compareTo(BigDecimal.ZERO) > 0) {
      return Severity.MEDIUM;
    }
    return Severity.LOW;
  }

  private Severity severityForPriceDiff(BigDecimal diffBps) {
    if (diffBps == null) {
      return Severity.LOW;
    }
    BigDecimal high = priceToleranceBps.multiply(BigDecimal.TEN);
    BigDecimal critical = priceToleranceBps.multiply(BigDecimal.valueOf(100));
    if (diffBps.compareTo(critical) >= 0) {
      return Severity.CRITICAL;
    }
    if (diffBps.compareTo(high) >= 0) {
      return Severity.HIGH;
    }
    return Severity.MEDIUM;
  }

  private static BigDecimal relativeDiffBps(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return BigDecimal.ZERO;
    }
    if (b.signum() == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal diff = a.subtract(b).abs();
    return diff.divide(b.abs(), MC).multiply(BigDecimal.valueOf(10_000), MC);
  }

  private static BigDecimal nullSafeMultiply(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return null;
    }
    return a.multiply(b, MC);
  }

  /**
   * Mutable aggregate used while folding fills into per-order rows. We keep running totals (qty and
   * qty-weighted notional) and finalise the VWAP at the end so we don't have to re-iterate.
   */
  private static final class Aggregate {
    final UUID orderId;
    final String symbol;
    final Side side;
    BigDecimal qty;
    BigDecimal notional;
    BigDecimal vwap;

    Aggregate(UUID orderId, String symbol, Side side, BigDecimal qty, BigDecimal notional) {
      this.orderId = orderId;
      this.symbol = symbol;
      this.side = side;
      this.qty = qty;
      this.notional = notional;
    }

    void add(BigDecimal qtyDelta, BigDecimal price) {
      if (qtyDelta == null) {
        return;
      }
      qty = qty.add(qtyDelta);
      if (price != null) {
        notional = notional.add(price.multiply(qtyDelta, MC), MC);
      }
    }

    void finishVwap() {
      if (qty.signum() == 0) {
        vwap = null;
        return;
      }
      vwap = notional.divide(qty, MC);
    }
  }
}
