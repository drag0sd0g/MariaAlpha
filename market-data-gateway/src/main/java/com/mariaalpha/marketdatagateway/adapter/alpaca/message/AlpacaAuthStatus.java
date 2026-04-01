package com.mariaalpha.marketdatagateway.adapter.alpaca.message;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum AlpacaAuthStatus {
  CONNECTED("connected"),
  AUTHENTICATED("authenticated");

  private static final Map<String, AlpacaAuthStatus> BY_MESSAGE =
      Stream.of(values())
          .collect(Collectors.toMap(AlpacaAuthStatus::getMessage, Function.identity()));

  private final String message;

  AlpacaAuthStatus(String message) {
    this.message = message;
  }

  public String getMessage() {
    return message;
  }

  public static AlpacaAuthStatus fromMessage(String message) {
    return BY_MESSAGE.get(message);
  }
}
