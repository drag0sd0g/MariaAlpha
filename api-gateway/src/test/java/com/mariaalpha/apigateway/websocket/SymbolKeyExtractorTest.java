package com.mariaalpha.apigateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SymbolKeyExtractorTest {

  @Test
  void extractsSymbol() {
    assertThat(SymbolKeyExtractor.extract("{\"symbol\":\"AAPL\",\"price\":1}", "symbol"))
        .isEqualTo("AAPL");
  }

  @Test
  void extractsOrderId() {
    assertThat(
            SymbolKeyExtractor.extract(
                "{\"orderId\":\"abc-123\",\"status\":\"FILLED\"}", "orderId"))
        .isEqualTo("abc-123");
  }

  @Test
  void returnsNullWhenFieldAbsent() {
    assertThat(SymbolKeyExtractor.extract("{\"price\":1}", "symbol")).isNull();
  }

  @Test
  void returnsNullOnMalformedJson() {
    assertThat(SymbolKeyExtractor.extract("{not json", "symbol")).isNull();
  }

  @Test
  void returnsNullOnEmpty() {
    assertThat(SymbolKeyExtractor.extract("", "symbol")).isNull();
    assertThat(SymbolKeyExtractor.extract(null, "symbol")).isNull();
  }

  @Test
  void handlesNestedJsonByReturningTopLevelOnly() {
    assertThat(
            SymbolKeyExtractor.extract(
                "{\"symbol\":\"AAPL\",\"meta\":{\"symbol\":\"OTHER\"}}", "symbol"))
        .isEqualTo("AAPL");
  }
}
