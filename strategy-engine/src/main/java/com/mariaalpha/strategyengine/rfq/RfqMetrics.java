package com.mariaalpha.strategyengine.rfq;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RfqMetrics {

  private static final String QUOTES_TOTAL = "mariaalpha_rfq_quotes_total";
  private static final String ACCEPT_TOTAL = "mariaalpha_rfq_accepts_total";
  private static final String SKEW_BPS = "mariaalpha_rfq_inventory_skew_bps";
  private static final String VOL_BPS = "mariaalpha_rfq_vol_widening_bps";
  private static final String ADV_BPS = "mariaalpha_rfq_adv_widening_bps";
  private static final String TOTAL_HALF_SPREAD_BPS = "mariaalpha_rfq_total_half_spread_bps";

  private final MeterRegistry registry;

  public RfqMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordQuote(RfqQuote quote) {
    Counter.builder(QUOTES_TOTAL)
        .description("Total RFQ quotes issued")
        .tag("symbol", quote.symbol())
        .register(registry)
        .increment();
    DistributionSummary.builder(SKEW_BPS)
        .description("Signed inventory skew (bps) applied to mid")
        .tag("symbol", quote.symbol())
        .register(registry)
        .record(quote.inventorySkewBps());
    DistributionSummary.builder(VOL_BPS)
        .description("Volatility-driven half-spread widening (bps)")
        .tag("symbol", quote.symbol())
        .register(registry)
        .record(quote.volWideningBps());
    DistributionSummary.builder(ADV_BPS)
        .description("ADV-relative half-spread widening (bps)")
        .tag("symbol", quote.symbol())
        .register(registry)
        .record(quote.advWideningBps());
    DistributionSummary.builder(TOTAL_HALF_SPREAD_BPS)
        .description("Total half-spread (bps) applied each side of mid")
        .tag("symbol", quote.symbol())
        .register(registry)
        .record(quote.totalHalfSpreadBps());
  }

  public void recordAccept(String symbol, String side) {
    Counter.builder(ACCEPT_TOTAL)
        .description("RFQ quotes accepted by the trader / client")
        .tag("symbol", symbol)
        .tag("side", side)
        .register(registry)
        .increment();
  }
}
