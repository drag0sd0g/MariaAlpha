package com.mariaalpha.executionengine.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-symbol reference data used by the sector / beta / ADV-participation risk checks: sector
 * classification, beta vs. a benchmark, and Average Daily Volume in shares.
 *
 * <p>The simulator carries a tiny fixed universe (AAPL, MSFT, GOOGL, AMZN, TSLA, NVDA), so the data
 * is statically configured under {@code execution-engine.risk.reference-data.*} rather than being
 * fetched from a vendor (Bloomberg, Refinitiv) at runtime. Production deployments would back this
 * with a periodic refresh from a corporate-actions / index-provider feed.
 *
 * <p>{@code defaults} provides fall-back values for any symbol missing from {@code symbols} so a
 * new ticker arriving via the strategy engine doesn't silently bypass the risk checks.
 */
@ConfigurationProperties(prefix = "execution-engine.risk.reference-data")
public record SymbolReferenceConfig(List<SymbolRef> symbols, SymbolRef defaults) {

  public record SymbolRef(String symbol, String sector, double beta, long adv) {}
}
