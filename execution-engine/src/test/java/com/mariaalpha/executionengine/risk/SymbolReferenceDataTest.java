package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.config.SymbolReferenceConfig;
import com.mariaalpha.executionengine.config.SymbolReferenceConfig.SymbolRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class SymbolReferenceDataTest {

  @Test
  void resolvesExplicitSymbolFromConfig() {
    var data = build(new SymbolRef("AAPL", "TECH", 1.2, 60_000_000L));
    assertThat(data.sectorOf("AAPL")).isEqualTo("TECH");
    assertThat(data.betaOf("AAPL")).isEqualTo(1.2);
    assertThat(data.advOf("AAPL")).isEqualTo(60_000_000L);
    assertThat(data.isMapped("AAPL")).isTrue();
  }

  @Test
  void fallsBackToDefaultsForUnknownSymbol() {
    var data = build(new SymbolRef("AAPL", "TECH", 1.2, 60_000_000L));
    assertThat(data.sectorOf("ZZZZ")).isEqualTo("UNKNOWN");
    assertThat(data.betaOf("ZZZZ")).isEqualTo(1.0);
    // Default ADV is conservatively 0 so the ADV check rejects until reference data is added.
    assertThat(data.advOf("ZZZZ")).isZero();
    assertThat(data.isMapped("ZZZZ")).isFalse();
  }

  @Test
  void handlesNullConfigGracefully() {
    var data = new SymbolReferenceData(null);
    data.load();
    assertThat(data.sectorOf("ZZZZ")).isEqualTo("UNKNOWN");
    assertThat(data.betaOf("ZZZZ")).isEqualTo(1.0);
    assertThat(data.advOf("ZZZZ")).isZero();
  }

  private SymbolReferenceData build(SymbolRef... refs) {
    var cfg = new SymbolReferenceConfig(List.of(refs), new SymbolRef("*", "UNKNOWN", 1.0, 0L));
    var data = new SymbolReferenceData(cfg);
    data.load();
    return data;
  }
}
