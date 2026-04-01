package com.mariaalpha.marketdatagateway.adapter.alpaca.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AlpacaTrade(
    @JsonProperty("T") String type,
    @JsonProperty("S") String symbol,
    @JsonProperty("p") BigDecimal price,
    @JsonProperty("s") long size,
    @JsonProperty("t") Instant timestamp,
    @JsonProperty("c") List<String> conditions,
    @JsonProperty("z") String tape) {
  public AlpacaTrade {
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
  }
}
