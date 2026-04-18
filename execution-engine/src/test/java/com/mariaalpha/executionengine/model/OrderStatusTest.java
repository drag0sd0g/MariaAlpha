package com.mariaalpha.executionengine.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

  @Test
  void newCanTransitionToSubmitted() {
    assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.SUBMITTED)).isTrue();
  }

  @Test
  void newCanTransitionToRejected() {
    assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.REJECTED)).isTrue();
  }

  @Test
  void newCannotTransitionToFilled() {
    assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.FILLED)).isFalse();
  }

  @Test
  void submittedCanTransitionToPartiallyFilled() {
    assertThat(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.PARTIALLY_FILLED)).isTrue();
  }

  @Test
  void submittedCanTransitionToFilled() {
    assertThat(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.FILLED)).isTrue();
  }

  @Test
  void submittedCanTransitionToCancelled() {
    assertThat(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
  }

  @Test
  void submittedCanTransitionToRejected() {
    assertThat(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.REJECTED)).isTrue();
  }

  @Test
  void partiallyFilledCanTransitionToFilled() {
    assertThat(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.FILLED)).isTrue();
  }

  @Test
  void partiallyFilledCanTransitionToCancelled() {
    assertThat(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
  }

  @Test
  void partiallyFilledCannotTransitionToSubmitted() {
    assertThat(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.SUBMITTED)).isFalse();
  }

  @Test
  void filledIsTerminal() {
    assertThat(OrderStatus.FILLED.allowedTransitions()).isEmpty();
  }

  @Test
  void cancelledIsTerminal() {
    assertThat(OrderStatus.CANCELLED.allowedTransitions()).isEmpty();
  }

  @Test
  void rejectedIsTerminal() {
    assertThat(OrderStatus.REJECTED.allowedTransitions()).isEmpty();
  }
}
