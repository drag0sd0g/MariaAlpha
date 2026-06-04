package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.SymbolReferenceConfig;
import com.mariaalpha.executionengine.config.SymbolReferenceConfig.SymbolRef;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Symbol-keyed lookup for sector / beta / ADV reference data.
 *
 * <p>Loads {@code execution-engine.risk.reference-data.symbols[]} at startup into an in-memory map
 * and falls back to {@code execution-engine.risk.reference-data.defaults} for any symbol not
 * explicitly configured. The fallback keeps the sector/beta/ADV risk checks safe — an unmapped
 * symbol lands in the {@code UNKNOWN} sector with the conservative default beta and a zero ADV (so
 * the ADV-participation check rejects every order on that symbol until reference data is added).
 */
@Component
public class SymbolReferenceData {

  private static final Logger LOG = LoggerFactory.getLogger(SymbolReferenceData.class);
  private static final SymbolRef HARD_DEFAULTS = new SymbolRef("*", "UNKNOWN", 1.0, 0L);

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

  /** Sector classification for {@code symbol}, or {@code defaults.sector()} if unknown. */
  public String sectorOf(String symbol) {
    return bySymbol.getOrDefault(symbol, defaults).sector();
  }

  /** Beta vs. benchmark for {@code symbol}, or {@code defaults.beta()} if unknown. */
  public double betaOf(String symbol) {
    return bySymbol.getOrDefault(symbol, defaults).beta();
  }

  /** Average Daily Volume (shares) for {@code symbol}, or {@code defaults.adv()} if unknown. */
  public long advOf(String symbol) {
    return bySymbol.getOrDefault(symbol, defaults).adv();
  }

  /** True iff explicit reference data was loaded for this symbol. */
  public boolean isMapped(String symbol) {
    return bySymbol.containsKey(symbol);
  }
}
