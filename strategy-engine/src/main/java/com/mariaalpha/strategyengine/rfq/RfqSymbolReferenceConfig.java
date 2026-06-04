package com.mariaalpha.strategyengine.rfq;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-symbol reference data for RFQ pricing: ADV used in the size/ADV spread widening term. Sectors
 * and betas are tracked alongside for forward-compatibility with later RFQ pricing extensions (e.g.
 * issuer-level beta tiering), but the pricing engine today only reads {@code adv}.
 *
 * <p>Mirrors the execution-engine's {@code SymbolReferenceConfig} layout intentionally — both
 * services own their own reference data per the §5.4 ownership rule. {@code defaults} provides a
 * conservative fall-back ADV for any unmapped symbol so the spread widening still kicks in for new
 * tickers.
 */
@ConfigurationProperties(prefix = "strategy-engine.rfq.reference-data")
public record RfqSymbolReferenceConfig(List<SymbolRef> symbols, SymbolRef defaults) {

  public record SymbolRef(String symbol, String sector, double beta, long adv) {}
}
