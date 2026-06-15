package com.mariaalpha.strategyengine.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.rfq.PositionLookup.PositionView;
import com.mariaalpha.strategyengine.rfq.RfqSymbolReferenceConfig.SymbolRef;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RfqPricingEngineTest {

  private static final RfqPricingConfig CONFIG =
      new RfqPricingConfig(4.0, 1.0, 1_000_000.0, 30.0, 0.5, 0.3, 10_000L, "http://x", 500L, 30);

  private MarketStateCache cache;
  private VolatilityTracker volTracker;
  private PositionLookup positionLookup;
  private RfqSymbolReferenceData refData;
  private RfqMetrics metrics;
  private RfqPricingEngine engine;
  private Clock clock;

  @BeforeEach
  void setUp() {
    cache = new MarketStateCache(CONFIG);
    volTracker = new VolatilityTracker(cache);
    positionLookup = mock(PositionLookup.class);
    refData =
        new RfqSymbolReferenceData(
            new RfqSymbolReferenceConfig(
                List.of(new SymbolRef("AAPL", "TECH", 1.2, 60_000_000L)),
                new SymbolRef("*", "UNKNOWN", 1.0, 0L)));
    refData.load();
    metrics = new RfqMetrics(new SimpleMeterRegistry());
    clock = Clock.fixed(Instant.parse("2026-03-24T14:00:00Z"), ZoneOffset.UTC);
    engine =
        new RfqPricingEngine(cache, volTracker, positionLookup, refData, CONFIG, metrics, clock);
  }

  @Test
  void rejectsUnknownSymbolWithNoMarketData() {
    when(positionLookup.fetch("AAPL")).thenReturn(PositionView.flat("AAPL"));
    assertThatThrownBy(() -> engine.quote("AAPL", 100))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No market data");
  }

  @Test
  void rejectsZeroQuantity() {
    assertThatThrownBy(() -> engine.quote("AAPL", 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void flatPositionYieldsSymmetricSpreadAroundMid() {
    primeBook("AAPL", "100.00", "100.20");
    when(positionLookup.fetch("AAPL")).thenReturn(PositionView.flat("AAPL"));

    var q = engine.quote("AAPL", 100);
    assertThat(q.inventorySkewBps()).isEqualTo(0.0);
    assertThat(q.marketMid()).isEqualByComparingTo("100.10");
    assertThat(q.adjustedMid()).isEqualByComparingTo("100.10");
    assertThat(q.bid().doubleValue()).isCloseTo(100.0800, within(0.001));
    assertThat(q.ask().doubleValue()).isCloseTo(100.1200, within(0.001));
  }

  @Test
  void longInventoryShiftsMidDown() {
    primeBook("AAPL", "100.00", "100.20");
    when(positionLookup.fetch("AAPL"))
        .thenReturn(
            new PositionView("AAPL", new BigDecimal("10000"), new BigDecimal("100.10"), true));

    var q = engine.quote("AAPL", 100);
    assertThat(q.inventorySkewBps()).isCloseTo(30.0, within(0.01));
    assertThat(q.adjustedMid().doubleValue()).isCloseTo(99.7997, within(0.01));
    assertThat(q.bid().doubleValue()).isLessThan(100.0800);
    assertThat(q.ask().doubleValue()).isLessThan(100.1200);
  }

  @Test
  void shortInventoryShiftsMidUp() {
    primeBook("AAPL", "100.00", "100.20");
    when(positionLookup.fetch("AAPL"))
        .thenReturn(
            new PositionView("AAPL", new BigDecimal("-10000"), new BigDecimal("100.10"), true));

    var q = engine.quote("AAPL", 100);
    assertThat(q.inventorySkewBps()).isCloseTo(-30.0, within(0.01));
    assertThat(q.adjustedMid().doubleValue()).isCloseTo(100.4003, within(0.01));
    assertThat(q.bid().doubleValue()).isGreaterThan(100.0800);
    assertThat(q.ask().doubleValue()).isGreaterThan(100.1200);
  }

  @Test
  void inventorySkewProportionalBelowCap() {
    primeBook("AAPL", "100.00", "100.20");
    var lowLambdaConfig =
        new RfqPricingConfig(
            4.0, 0.0001, 1_000_000.0, 30.0, 0.0, 0.0, 10_000L, "http://x", 500L, 30);
    var lowEngine =
        new RfqPricingEngine(
            cache, volTracker, positionLookup, refData, lowLambdaConfig, metrics, clock);
    when(positionLookup.fetch("AAPL"))
        .thenReturn(
            new PositionView("AAPL", new BigDecimal("1000"), new BigDecimal("100.10"), true));

    var q = lowEngine.quote("AAPL", 100);
    assertThat(q.inventorySkewBps()).isCloseTo(0.1001, within(0.005));
  }

  @Test
  void higherVolatilityWidensSpread() {
    when(positionLookup.fetch("AAPL")).thenReturn(PositionView.flat("AAPL"));
    primeBook("AAPL", "100.00", "100.20");
    primeBook("AAPL", "100.01", "100.21");
    primeBook("AAPL", "100.00", "100.20");
    var calm = engine.quote("AAPL", 100);

    primeBook("AAPL", "102.00", "102.20");
    primeBook("AAPL", "98.00", "98.20");
    primeBook("AAPL", "103.00", "103.20");
    var volatileQuote = engine.quote("AAPL", 100);

    assertThat(volatileQuote.volWideningBps()).isGreaterThan(calm.volWideningBps());
    double calmHalf =
        calm.bid().doubleValue() == 0 ? 0 : calm.ask().subtract(calm.bid()).doubleValue();
    double volHalf = volatileQuote.ask().subtract(volatileQuote.bid()).doubleValue();
    assertThat(volHalf).isGreaterThan(calmHalf);
  }

  @Test
  void largeSizeRelativeToAdvWidensSpread() {
    when(positionLookup.fetch("AAPL")).thenReturn(PositionView.flat("AAPL"));
    primeBook("AAPL", "100.00", "100.20");

    var small = engine.quote("AAPL", 100);
    var big = engine.quote("AAPL", 600_000);

    assertThat(big.advParticipationFraction()).isGreaterThan(small.advParticipationFraction());
    assertThat(big.advWideningBps()).isGreaterThan(small.advWideningBps());
    assertThat(big.totalHalfSpreadBps()).isGreaterThan(small.totalHalfSpreadBps());
  }

  @Test
  void unknownSymbolFallsBackToDefaultAdvZero() {
    primeBook("XYZ", "50.00", "50.10");
    when(positionLookup.fetch("XYZ")).thenReturn(PositionView.flat("XYZ"));
    var q = engine.quote("XYZ", 100);
    assertThat(q.advParticipationFraction()).isEqualTo(0.0);
    assertThat(q.advWideningBps()).isEqualTo(0.0);
  }

  @Test
  void unavailableOrderManagerStillReturnsAQuote() {
    primeBook("AAPL", "100.00", "100.20");
    when(positionLookup.fetch("AAPL")).thenReturn(PositionView.unavailable("AAPL"));
    var q = engine.quote("AAPL", 100);
    assertThat(q.inventorySkewBps()).isEqualTo(0.0);
    assertThat(q.bid()).isNotNull();
    assertThat(q.ask()).isNotNull();
  }

  @Test
  void quoteExpiresAfterValidityWindow() {
    primeBook("AAPL", "100.00", "100.20");
    when(positionLookup.fetch("AAPL")).thenReturn(PositionView.flat("AAPL"));
    var q = engine.quote("AAPL", 100);
    assertThat(q.expiresAt().toEpochMilli() - q.issuedAt().toEpochMilli())
        .isEqualTo(CONFIG.quoteValidityMs());
  }

  @Test
  void quoteBidLessThanAsk() {
    primeBook("AAPL", "100.00", "100.20");
    when(positionLookup.fetch("AAPL")).thenReturn(PositionView.flat("AAPL"));
    var q = engine.quote("AAPL", 100);
    assertThat(q.bid().compareTo(q.ask())).isLessThan(0);
  }

  @Test
  void blankSymbolRejected() {
    assertThatThrownBy(() -> engine.quote("", 100)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.quote(null, 100)).isInstanceOf(IllegalArgumentException.class);
  }

  private void primeBook(String symbol, String bid, String ask) {
    cache.onTick(
        new MarketTick(
            symbol,
            Instant.now(clock),
            EventType.QUOTE,
            BigDecimal.ZERO,
            0L,
            new BigDecimal(bid),
            new BigDecimal(ask),
            100L,
            100L,
            0L,
            DataSource.SIMULATED,
            false));
  }
}
