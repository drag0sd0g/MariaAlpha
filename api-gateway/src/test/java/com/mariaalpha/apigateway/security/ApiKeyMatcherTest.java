package com.mariaalpha.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyMatcherTest {

  @Test
  void matchesIdenticalKeys() {
    assertThat(ApiKeyMatcher.matches("super-secret", "super-secret")).isTrue();
  }

  @Test
  void rejectsDifferentKeys() {
    assertThat(ApiKeyMatcher.matches("super-secret", "wrong")).isFalse();
  }

  @Test
  void rejectsCaseDifference() {
    assertThat(ApiKeyMatcher.matches("Secret", "secret")).isFalse();
  }

  @Test
  void rejectsNullPresented() {
    assertThat(ApiKeyMatcher.matches("k", null)).isFalse();
  }

  @Test
  void rejectsEmptyPresented() {
    assertThat(ApiKeyMatcher.matches("k", "")).isFalse();
  }

  @Test
  void rejectsUnconfiguredExpected() {
    assertThat(ApiKeyMatcher.matches(null, "anything")).isFalse();
    assertThat(ApiKeyMatcher.matches("", "anything")).isFalse();
    assertThat(ApiKeyMatcher.matches("   ", "anything")).isFalse();
  }

  @Test
  void rejectsKeyOfDifferentLength() {
    // MessageDigest.isEqual returns false for different lengths.
    assertThat(ApiKeyMatcher.matches("longerkey", "short")).isFalse();
  }
}
