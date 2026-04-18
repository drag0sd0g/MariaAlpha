package com.mariaalpha.executionengine.model;

public record ValidationResult(boolean valid, String reason) {

  public static ValidationResult ok() {
    return new ValidationResult(true, "");
  }

  public static ValidationResult fail(String reason) {
    return new ValidationResult(false, reason);
  }
}
