package com.mariaalpha.marketdatagateway.adapter.alpaca.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AlpacaBarsResponse(
    @JsonProperty("bars") List<AlpacaBar> bars,
    @JsonProperty("symbol") String symbol,
    @JsonProperty("next_page_token") String nextPageToken) {
  public AlpacaBarsResponse {
    bars = bars == null ? List.of() : List.copyOf(bars);
  }
}
