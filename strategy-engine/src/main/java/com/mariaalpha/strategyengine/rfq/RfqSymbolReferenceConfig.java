package com.mariaalpha.strategyengine.rfq;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strategy-engine.rfq.reference-data")
public record RfqSymbolReferenceConfig(List<SymbolRef> symbols, SymbolRef defaults) {

  public record SymbolRef(String symbol, String sector, double beta, long adv) {}
}
