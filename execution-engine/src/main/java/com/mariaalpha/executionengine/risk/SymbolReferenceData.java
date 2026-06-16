package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.SymbolReferenceConfig;
import com.mariaalpha.executionengine.config.SymbolReferenceConfig.SymbolRef;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SymbolReferenceData {

  private static final Logger LOG = LoggerFactory.getLogger(SymbolReferenceData.class);
  private static final SymbolRef HARD_DEFAULTS = new SymbolRef("*", "UNKNOWN", 1.0, 0L, 0.0);

  private final SymbolReferenceConfig config;
  private final Map<String, SymbolRef> bySymbol = new HashMap<>();
  private final SymbolRef defaults;

  public SymbolReferenceData(SymbolReferenceConfig config) {
    this.config = config;
    this.defaults = config != null && config.defaults() != null ? config.defaults() : HARD_DEFAULTS;
  }

  @PostConstruct
  void load() {
    var refs = config == null ? null : config.symbols();
    if (refs == null) {
      LOG.warn(
          "execution-engine.risk.reference-data.symbols is empty — every symbol will use defaults"
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
        "SymbolReferenceData loaded {} symbols; defaults sector={} beta={} adv={}",
        bySymbol.size(),
        defaults.sector(),
        defaults.beta(),
        defaults.adv());
  }

  public String sectorOf(String symbol) {
    return bySymbol.getOrDefault(symbol, defaults).sector();
  }

  public double betaOf(String symbol) {
    return bySymbol.getOrDefault(symbol, defaults).beta();
  }

  public long advOf(String symbol) {
    return bySymbol.getOrDefault(symbol, defaults).adv();
  }

  public double annualizedVolatilityOf(String symbol) {
    return bySymbol.getOrDefault(symbol, defaults).annualizedVolatility();
  }

  public boolean isMapped(String symbol) {
    return bySymbol.containsKey(symbol);
  }
}
