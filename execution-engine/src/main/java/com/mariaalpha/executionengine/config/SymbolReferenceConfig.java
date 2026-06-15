package com.mariaalpha.executionengine.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "execution-engine.risk.reference-data")
public record SymbolReferenceConfig(List<SymbolRef> symbols, SymbolRef defaults) {

  public record SymbolRef(
      String symbol, String sector, double beta, long adv, double annualizedVolatility) {

    @ConstructorBinding
    public SymbolRef {}

    public SymbolRef(String symbol, String sector, double beta, long adv) {
      this(symbol, sector, beta, adv, 0.0);
    }
  }
}
