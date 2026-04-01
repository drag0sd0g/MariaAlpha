package com.mariaalpha.marketdatagateway.adapter.alpaca.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AlpacaQuote(
    @JsonProperty("T") String type,
    @JsonProperty("S") String symbol,
    @JsonProperty("bp") BigDecimal bidPrice,
    @JsonProperty("ap") BigDecimal askPrice,
    @JsonProperty("bs") long bidSize,
    @JsonProperty("as") long askSize,
    @JsonProperty("t") Instant timestamp,
    @JsonProperty("c") List<String> conditions,
    @JsonProperty("z") String tape) {
  public AlpacaQuote {
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
  }
}
