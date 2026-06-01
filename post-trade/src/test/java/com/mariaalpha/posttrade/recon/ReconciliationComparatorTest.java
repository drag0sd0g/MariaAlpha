package com.mariaalpha.posttrade.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.recon.ReconciliationBreak.BreakType;
import com.mariaalpha.posttrade.recon.ReconciliationBreak.Severity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationComparatorTest {

  private static final LocalDate D = LocalDate.of(2026, 6, 1);
  private static final Instant TS = Instant.parse("2026-06-01T15:30:00Z");

  // 1 bps tolerance on price, 0.001 tolerance on qty, $10k HIGH / $100k CRITICAL severity.
  private final ReconciliationComparator comparator =
      new ReconciliationComparator(
          new BigDecimal("1.0"),
          new BigDecimal("0.001"),
          new BigDecimal("10000"),
          new BigDecimal("100000"));

  @Test
  void identicalFillsProduceNoBreaks() {
    var internal = List.of(fill("alpaca-1", "AAPL", Side.BUY, "180.05", "100"));
    var external = List.of(ext("alpaca-1", "AAPL", Side.BUY, "180.05", "100"));
    assertThat(comparator.compare(D, internal, external)).isEmpty();
  }

  @Test
  void emptyBothSidesProducesNoBreaks() {
    assertThat(comparator.compare(D, List.of(), List.of())).isEmpty();
  }

  @Test
  void externalOnlyFillIsMissingFill() {
    var external = List.of(ext("alpaca-1", "AAPL", Side.BUY, "180.05", "100"));
    var breaks = comparator.compare(D, List.of(), external);
    assertThat(breaks).hasSize(1);
    assertThat(breaks.get(0).breakType()).isEqualTo(BreakType.MISSING_FILL);
    assertThat(breaks.get(0).symbol()).isEqualTo("AAPL");
    assertThat(breaks.get(0).externalQty()).isEqualByComparingTo("100");
    assertThat(breaks.get(0).internalQty()).isNull();
  }

  @Test
  void internalOnlyFillIsExtraFill() {
    var internal = List.of(fill("alpaca-1", "AAPL", Side.BUY, "180.05", "100"));
    var breaks = comparator.compare(D, internal, List.of());
    assertThat(breaks).hasSize(1);
    assertThat(breaks.get(0).breakType()).isEqualTo(BreakType.EXTRA_FILL);
    assertThat(breaks.get(0).internalQty()).isEqualByComparingTo("100");
    assertThat(breaks.get(0).externalQty()).isNull();
  }

  @Test
  void quantityMismatchIsDetected() {
    var internal = List.of(fill("alpaca-1", "AAPL", Side.BUY, "180.05", "100"));
    var external = List.of(ext("alpaca-1", "AAPL", Side.BUY, "180.05", "90"));
    var breaks = comparator.compare(D, internal, external);
    assertThat(breaks).hasSize(1);
    assertThat(breaks.get(0).breakType()).isEqualTo(BreakType.QUANTITY_MISMATCH);
    assertThat(breaks.get(0).internalQty()).isEqualByComparingTo("100");
    assertThat(breaks.get(0).externalQty()).isEqualByComparingTo("90");
  }

  @Test
  void quantityDifferenceBelowToleranceIgnored() {
    var internal = List.of(fill("alpaca-1", "AAPL", Side.BUY, "180.05", "100"));
    var external = List.of(ext("alpaca-1", "AAPL", Side.BUY, "180.05", "100.0005"));
    assertThat(comparator.compare(D, internal, external)).isEmpty();
  }

  @Test
  void priceMismatchAboveToleranceIsDetected() {
    // 180.05 vs 180.08 → 1.66 bps, above 1.0 bps tolerance
    var internal = List.of(fill("alpaca-1", "AAPL", Side.BUY, "180.05", "100"));
    var external = List.of(ext("alpaca-1", "AAPL", Side.BUY, "180.08", "100"));
    var breaks = comparator.compare(D, internal, external);
    assertThat(breaks).hasSize(1);
    assertThat(breaks.get(0).breakType()).isEqualTo(BreakType.PRICE_MISMATCH);
    assertThat(breaks.get(0).internalPrice()).isEqualByComparingTo("180.05");
    assertThat(breaks.get(0).externalPrice()).isEqualByComparingTo("180.08");
  }

  @Test
  void priceDifferenceBelowToleranceIgnored() {
    // 180.05 vs 180.06 → 0.555 bps, below 1.0 bps tolerance
    var internal = List.of(fill("alpaca-1", "AAPL", Side.BUY, "180.05", "100"));
    var external = List.of(ext("alpaca-1", "AAPL", Side.BUY, "180.06", "100"));
    assertThat(comparator.compare(D, internal, external)).isEmpty();
  }

  @Test
  void aggregatesMultipleFillsPerOrder() {
    // Two internal fills for same alpaca-1 order, total = 100 @ vwap 180.05
    var internal =
        List.of(
            fill("alpaca-1", "AAPL", Side.BUY, "180.00", "50"),
            fill("alpaca-1", "AAPL", Side.BUY, "180.10", "50"));
    var external = List.of(ext("alpaca-1", "AAPL", Side.BUY, "180.05", "100"));
    assertThat(comparator.compare(D, internal, external)).isEmpty();
  }

  @Test
  void severityScalesWithNotionalForMissingFill() {
    // $1k → MEDIUM, $10k → HIGH, $100k → CRITICAL
    var lowBreak =
        comparator.compare(D, List.of(), List.of(ext("o1", "AAPL", Side.BUY, "10", "100"))).get(0);
    var highBreak =
        comparator.compare(D, List.of(), List.of(ext("o2", "AAPL", Side.BUY, "100", "100"))).get(0);
    var criticalBreak =
        comparator
            .compare(D, List.of(), List.of(ext("o3", "AAPL", Side.BUY, "1000", "100")))
            .get(0);
    assertThat(lowBreak.severity()).isEqualTo(Severity.MEDIUM);
    assertThat(highBreak.severity()).isEqualTo(Severity.HIGH);
    assertThat(criticalBreak.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void severityScalesWithPriceDiffMagnitudeForPriceMismatch() {
    // 1.66 bps diff → just above tolerance → MEDIUM (less than 10× = 10 bps)
    var medium = priceMismatch("180.05", "180.08");
    // 100 vs 110 → 909 bps diff → way above 100× tolerance (100 bps) → CRITICAL
    var critical = priceMismatch("100", "110");
    assertThat(medium.severity()).isEqualTo(Severity.MEDIUM);
    assertThat(critical.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void fillsMatchByClientOrderIdWhenExchangeOrderIdMissing() {
    var clientId = UUID.randomUUID().toString();
    var internal =
        List.of(
            new InternalFill(
                UUID.randomUUID(),
                UUID.fromString(clientId),
                null,
                clientId,
                "AAPL",
                Side.BUY,
                new BigDecimal("180.05"),
                new BigDecimal("100"),
                TS));
    var external =
        List.of(
            new ExternalFill(
                "ext-1",
                null,
                clientId,
                "AAPL",
                Side.BUY,
                new BigDecimal("180.05"),
                new BigDecimal("100"),
                TS));
    assertThat(comparator.compare(D, internal, external)).isEmpty();
  }

  @Test
  void mismatchedOrdersAreReportedSeparately() {
    var internal =
        List.of(
            fill("alpaca-1", "AAPL", Side.BUY, "180.05", "100"),
            fill("alpaca-2", "MSFT", Side.SELL, "415.00", "50"));
    var external = List.of(ext("alpaca-3", "GOOGL", Side.BUY, "140.00", "200"));
    var breaks = comparator.compare(D, internal, external);
    assertThat(breaks).hasSize(3); // 2 EXTRA_FILL + 1 MISSING_FILL
    long extra = breaks.stream().filter(b -> b.breakType() == BreakType.EXTRA_FILL).count();
    long missing = breaks.stream().filter(b -> b.breakType() == BreakType.MISSING_FILL).count();
    assertThat(extra).isEqualTo(2);
    assertThat(missing).isEqualTo(1);
  }

  @Test
  void bothQtyAndPriceMismatchProduceTwoBreaks() {
    var internal = List.of(fill("alpaca-1", "AAPL", Side.BUY, "180.00", "100"));
    var external = List.of(ext("alpaca-1", "AAPL", Side.BUY, "181.00", "90"));
    var breaks = comparator.compare(D, internal, external);
    assertThat(breaks).hasSize(2);
    assertThat(breaks.stream().map(ReconciliationBreak::breakType))
        .containsExactlyInAnyOrder(BreakType.QUANTITY_MISMATCH, BreakType.PRICE_MISMATCH);
  }

  @Test
  void fillsWithNoMatchKeyAreDroppedRatherThanThrowing() {
    var internal =
        List.of(
            new InternalFill(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null, // no client or exchange id
                "AAPL",
                Side.BUY,
                new BigDecimal("180.05"),
                new BigDecimal("100"),
                TS));
    assertThat(comparator.compare(D, internal, List.of())).isEmpty();
  }

  // --- helpers ---

  private static InternalFill fill(
      String exchangeOrderId, String symbol, Side side, String price, String qty) {
    return new InternalFill(
        UUID.randomUUID(),
        UUID.randomUUID(),
        exchangeOrderId,
        UUID.randomUUID().toString(),
        symbol,
        side,
        new BigDecimal(price),
        new BigDecimal(qty),
        TS);
  }

  private static ExternalFill ext(
      String exchangeOrderId, String symbol, Side side, String price, String qty) {
    return new ExternalFill(
        UUID.randomUUID().toString(),
        exchangeOrderId,
        UUID.randomUUID().toString(),
        symbol,
        side,
        new BigDecimal(price),
        new BigDecimal(qty),
        TS);
  }

  private ReconciliationBreak priceMismatch(String internalPrice, String externalPrice) {
    var internal = List.of(fill("alpaca-1", "AAPL", Side.BUY, internalPrice, "100"));
    var external = List.of(ext("alpaca-1", "AAPL", Side.BUY, externalPrice, "100"));
    var breaks = comparator.compare(D, internal, external);
    return breaks.stream()
        .filter(b -> b.breakType() == BreakType.PRICE_MISMATCH)
        .findFirst()
        .orElseThrow();
  }
}
