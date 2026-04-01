package com.mariaalpha.marketdatagateway.adapter.alpaca.message;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum AlpacaMessageType {
  SUCCESS("success"),
  ERROR("error"),
  TRADE("t"),
  QUOTE("q"),
  SUBSCRIPTION("subscription");

  private static final Map<String, AlpacaMessageType> BY_CODE =
      Stream.of(values())
          .collect(Collectors.toMap(AlpacaMessageType::getCode, Function.identity()));

  private final String code;

  AlpacaMessageType(String code) {
    this.code = code;
  }

  public String getCode() {
    return code;
  }

  public static AlpacaMessageType fromCode(String code) {
    return BY_CODE.get(code);
  }
}
