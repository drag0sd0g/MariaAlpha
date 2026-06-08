package com.mariaalpha.executionengine.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Per-symbol reference data used by the sector / beta / ADV-participation / intraday-VaR risk
 * checks: sector classification, beta vs. a benchmark, Average Daily Volume in shares, and
 * annualised volatility of log-returns (decimal — 0.25 means 25%/yr).
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

  public record SymbolRef(
      String symbol, String sector, double beta, long adv, double annualizedVolatility) {

    /**
     * Pin Spring Boot's {@code @ConfigurationProperties} binder to this 5-arg canonical
     * constructor. Without this annotation the binder can't pick a constructor when the record has
     * multiple, falls back to Java-bean mode, and fails with {@code NoSuchMethodException:
     * <init>()} — which crashes the execution-engine context at startup. Boot-3 documented fix.
     */
    @ConstructorBinding
    public SymbolRef {}

    /**
     * Legacy 4-arg constructor for call sites that predate the volatility field (roadmap 3.5.1).
     * Defaults {@code annualizedVolatility} to 0 — which the VaR check reads as "unknown" and
     * contributes zero risk for the symbol so the check stays safe-by-default.
     */
    public SymbolRef(String symbol, String sector, double beta, long adv) {
      this(symbol, sector, beta, adv, 0.0);
    }
  }
}
