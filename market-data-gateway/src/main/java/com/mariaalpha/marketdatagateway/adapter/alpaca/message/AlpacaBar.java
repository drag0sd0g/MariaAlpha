package com.mariaalpha.marketdatagateway.adapter.alpaca.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;

public record AlpacaBar(
    @JsonProperty("t") Instant timestamp,
    @JsonProperty("o") BigDecimal open,
    @JsonProperty("h") BigDecimal high,
    @JsonProperty("l") BigDecimal low,
    @JsonProperty("c") BigDecimal close,
    @JsonProperty("v") long volume,
    @JsonProperty("n") long tradeCount,
    @JsonProperty("vw") BigDecimal vwap) {}
