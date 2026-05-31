package com.mariaalpha.strategyengine.rfq;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.strategyengine.rfq.RfqSymbolReferenceConfig.SymbolRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class RfqSymbolReferenceDataTest {

  @Test
  void returnsConfiguredAdvForKnownSymbol() {
    var data =
        new RfqSymbolReferenceData(
            new RfqSymbolReferenceConfig(
                List.of(new SymbolRef("AAPL", "TECH", 1.2, 60_000_000L)),
                new SymbolRef("*", "UNKNOWN", 1.0, 0L)));
    data.load();
    assertThat(data.advOf("AAPL")).isEqualTo(60_000_000L);
    assertThat(data.sectorOf("AAPL")).isEqualTo("TECH");
    assertThat(data.betaOf("AAPL")).isEqualTo(1.2);
    assertThat(data.isMapped("AAPL")).isTrue();
  }

  @Test
  void fallsBackToDefaultsForUnknownSymbol() {
    var data =
        new RfqSymbolReferenceData(
            new RfqSymbolReferenceConfig(
                List.of(new SymbolRef("AAPL", "TECH", 1.2, 60_000_000L)),
                new SymbolRef("*", "UNKNOWN", 1.0, 1234L)));
    data.load();
    assertThat(data.advOf("XYZ")).isEqualTo(1234L);
    assertThat(data.sectorOf("XYZ")).isEqualTo("UNKNOWN");
    assertThat(data.isMapped("XYZ")).isFalse();
  }

  @Test
  void handlesNullSymbolsList() {
    var data =
        new RfqSymbolReferenceData(
            new RfqSymbolReferenceConfig(null, new SymbolRef("*", "UNKNOWN", 1.0, 0L)));
    data.load();
    assertThat(data.advOf("AAPL")).isZero();
  }

  @Test
  void handlesNullConfig() {
    var data = new RfqSymbolReferenceData(null);
    data.load();
    assertThat(data.advOf("AAPL")).isZero();
    assertThat(data.sectorOf("AAPL")).isEqualTo("UNKNOWN");
  }
}
