package com.mariaalpha.posttrade.service;

public class OrderManagerUnavailableException extends RuntimeException {

  public OrderManagerUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
