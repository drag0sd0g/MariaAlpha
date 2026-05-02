package com.mariaalpha.apigateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ApiKeyMatcher {

  private ApiKeyMatcher() {}

  /**
   * Constant-time comparison. Returns false if either argument is null/empty so callers don't have
   * to special-case absence — a missing presented key never matches.
   *
   * <p>MessageDigest.isEqual is the JDK's documented constant-time equal-length comparator since
   * Java 7. (String.equals is not constant-time and short-circuits on first mismatch.)
   */
  public static boolean matches(String expected, String presented) {
    if (expected == null || expected.isBlank() || presented == null || presented.isEmpty()) {
      return false;
    }
    byte[] a = expected.getBytes(StandardCharsets.UTF_8);
    byte[] b = presented.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(a, b);
  }
}
