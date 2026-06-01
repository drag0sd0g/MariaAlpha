package com.mariaalpha.posttrade.recon;

/** Wraps any failure talking to Alpaca's activities API. */
public class AlpacaActivitiesException extends RuntimeException {

  public AlpacaActivitiesException(String message) {
    super(message);
  }

  public AlpacaActivitiesException(String message, Throwable cause) {
    super(message, cause);
  }
}
