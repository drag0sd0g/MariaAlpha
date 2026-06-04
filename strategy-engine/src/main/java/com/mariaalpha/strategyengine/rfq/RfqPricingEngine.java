package com.mariaalpha.strategyengine.rfq;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Two-way RFQ quote generator combining inventory skew and volatility + size/ADV widening.
 *
 * <p>Pricing pipeline per quote request:
 *
 * <ol>
 *   <li>Fetch market mid from {@link MarketStateCache}; reject if no book yet.
 *   <li>Fetch current desk position from {@link PositionLookup}; treat missing as flat.
 *   <li>Compute inventory skew on the <em>mid</em> — long inventory pushes mid down (offload),
 *       short pushes it up (cover). Capped at {@code inventoryMaxSkewBps} so a runaway position
 *       can't shove the quote arbitrarily far.
 *   <li>Compute realised volatility (bps) over the rolling window via {@link VolatilityTracker}.
 *   <li>Compute size-as-percent-of-ADV.
 *   <li>Assemble half-spread = base/2 + volScalar × vol + advScalar × (size/ADV in bps).
 *   <li>Bid = adjusted_mid × (1 − half-spread); Ask = adjusted_mid × (1 + half-spread).
 * </ol>
 */
@Component
public class RfqPricingEngine {

  private static final Logger LOG = LoggerFactory.getLogger(RfqPricingEngine.class);

  private final MarketStateCache marketState;
  private final VolatilityTracker volatilityTracker;
  private final PositionLookup positionLookup;
  private final RfqSymbolReferenceData refData;
  private final RfqPricingConfig config;
  private final RfqMetrics metrics;
  private final Clock clock;

  @Autowired
  public RfqPricingEngine(
      MarketStateCache marketState,
      VolatilityTracker volatilityTracker,
      PositionLookup positionLookup,
      RfqSymbolReferenceData refData,
      RfqPricingConfig config,
      RfqMetrics metrics) {
    this(
        marketState,
        volatilityTracker,
        positionLookup,
        refData,
        config,
        metrics,
        Clock.systemUTC());
  }

  RfqPricingEngine(
      MarketStateCache marketState,
      VolatilityTracker volatilityTracker,
      PositionLookup positionLookup,
      RfqSymbolReferenceData refData,
      RfqPricingConfig config,
      RfqMetrics metrics,
      Clock clock) {
    this.marketState = marketState;
    this.volatilityTracker = volatilityTracker;
    this.positionLookup = positionLookup;
    this.refData = refData;
    this.config = config;
    this.metrics = metrics;
    this.clock = clock;
  }

  public RfqQuote quote(String symbol, int quantity) {
    if (symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("symbol is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be > 0");
    }

    var snapshotOpt = marketState.snapshot(symbol);
    if (snapshotOpt.isEmpty() || snapshotOpt.get().mid().signum() <= 0) {
      throw new IllegalStateException("No market data available for " + symbol);
    }
    var snapshot = snapshotOpt.get();
    BigDecimal marketMid = snapshot.mid();

    // --- Inventory skew -------------------------------------------------------
    var position = positionLookup.fetch(symbol);
    double netQty = position.netQuantity().doubleValue();
    double inventoryNotional = netQty * marketMid.doubleValue();
    double rawSkewFraction =
        config.inventoryLambda() * (inventoryNotional / config.inventoryNeutralNotional());
    double rawSkewBps = rawSkewFraction * 10_000.0;
    double cappedSkewBps =
        clamp(rawSkewBps, -config.inventoryMaxSkewBps(), config.inventoryMaxSkewBps());
    BigDecimal adjustedMid = marketMid.multiply(BigDecimal.valueOf(1.0 - cappedSkewBps / 10_000.0));

    // --- Volatility widening --------------------------------------------------
    double realizedVolBps = volatilityTracker.realizedVolBps(symbol);
    double volWideningBps = config.volScalar() * realizedVolBps;

    // --- ADV widening ---------------------------------------------------------
    long adv = refData.advOf(symbol);
    double advFraction = adv <= 0 ? 0.0 : (double) quantity / (double) adv;
    double advWideningBps = config.advScalar() * advFraction * 10_000.0;

    // --- Assemble spread ------------------------------------------------------
    double baseHalfSpreadBps = config.baseSpreadBps() / 2.0;
    double totalHalfSpreadBps = baseHalfSpreadBps + volWideningBps + advWideningBps;

    BigDecimal half = BigDecimal.valueOf(totalHalfSpreadBps / 10_000.0);
    BigDecimal bid =
        adjustedMid.multiply(BigDecimal.ONE.subtract(half)).setScale(4, RoundingMode.HALF_UP);
    BigDecimal ask =
        adjustedMid.multiply(BigDecimal.ONE.add(half)).setScale(4, RoundingMode.HALF_UP);
    BigDecimal roundedAdjustedMid = adjustedMid.setScale(4, RoundingMode.HALF_UP);
    BigDecimal roundedMarketMid = marketMid.setScale(4, RoundingMode.HALF_UP);

    Instant now = Instant.now(clock);
    var quote =
        new RfqQuote(
            UUID.randomUUID(),
            symbol,
            quantity,
            roundedMarketMid,
            roundedAdjustedMid,
            bid,
            ask,
            netQty,
            inventoryNotional,
            cappedSkewBps,
            realizedVolBps,
            volWideningBps,
            advFraction,
            advWideningBps,
            baseHalfSpreadBps,
            totalHalfSpreadBps,
            adv,
            now,
            now.plusMillis(config.quoteValidityMs()));

    metrics.recordQuote(quote);
    LOG.info(
        "RFQ quoted {} {}sh mid={} adjMid={} bid={} ask={} skewBps={} volBps={} advBps={}",
        symbol,
        quantity,
        roundedMarketMid,
        roundedAdjustedMid,
        bid,
        ask,
        String.format("%.3f", cappedSkewBps),
        String.format("%.3f", volWideningBps),
        String.format("%.3f", advWideningBps));
    return quote;
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
