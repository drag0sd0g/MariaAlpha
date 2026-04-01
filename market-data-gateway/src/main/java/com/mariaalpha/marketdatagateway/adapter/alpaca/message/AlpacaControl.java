package com.mariaalpha.marketdatagateway.adapter.alpaca.message;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AlpacaControl(
    @JsonProperty("T") String type,
    @JsonProperty("msg") String message,
    @JsonProperty("code") Integer code) {}
