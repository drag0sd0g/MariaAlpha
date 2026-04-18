package com.mariaalpha.executionengine.model;

public record RiskCheckResult(boolean passed, String checkName, String reason) {

  public static RiskCheckResult pass(String checkName) {
    return new RiskCheckResult(true, checkName, "");
  }

  public static RiskCheckResult fail(String checkName, String reason) {
    return new RiskCheckResult(false, checkName, reason);
  }
}
