package com.mariaalpha.strategyengine.rfq;

import com.mariaalpha.strategyengine.rfq.RfqSymbolReferenceConfig.SymbolRef;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Symbol-keyed ADV / sector / beta lookup used by the RFQ pricing engine. Implementation parallels
 * the execution-engine's {@code SymbolReferenceData} — kept service-local because each service owns
 * its own config per the TDD §5.4 ownership rule.
 */
@Component
public class RfqSymbolReferenceData {

  private static final Logger LOG = LoggerFactory.getLogger(RfqSymbolReferenceData.class);
  private static final SymbolRef HARD_DEFAULTS = new SymbolRef("*", "UNKNOWN", 1.0, 0L);

  private final RfqSymbolReferenceConfig config;
  private final Map<String, SymbolRef> bySymbol = new HashMap<>();
  private final SymbolRef defaults;

  public RfqSymbolReferenceData(RfqSymbolReferenceConfig config) {
    this.config = config;
    this.defaults = config != null && config.defaults() != null ? config.defaults() : HARD_DEFAULTS;
  }

  @PostConstruct
  void load() {
    var refs = config == null ? null : config.symbols();
    if (refs == null) {
      LOG.warn(
          "strategy-engine.rfq.reference-data.symbols is empty — every symbol uses defaults"
              + " (sector={}, beta={}, adv={})",
          defaults.sector(),
          defaults.beta(),
          defaults.adv());
      return;
    }
    for (var ref : refs) {
      bySymbol.put(ref.symbol(), ref);
    }
    LOG.info(
        "RfqSymbolReferenceData loaded {} symbols; defaults sector={} beta={} adv={}",
        bySymbol.size(),
        defaults.sector(),
        defaults.beta(),
        defaults.adv());
  }

  public long advOf(String symbol) {
    return bySymbol.getOrDefault(symbol, defaults).adv();
  }

  public String sectorOf(String symbol) {
    return bySymbol.getOrDefault(symbol, defaults).sector();
  }

  public double betaOf(String symbol) {
    return bySymbol.getOrDefault(symbol, defaults).beta();
  }

  public boolean isMapped(String symbol) {
    return bySymbol.containsKey(symbol);
  }
}
