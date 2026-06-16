package com.mariaalpha.executionengine.basket;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.Side;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BasketStateTest {

  private BasketState newBasket() {
    return new BasketState("b1", "rebalance", Instant.now());
  }

  @Test
  void freshlySubmittedBasket_isSubmitted() {
    var state = newBasket();
    state.addLeg("leg-1", "AAPL", Side.BUY, 100);
    state.addLeg("leg-2", "MSFT", Side.SELL, 50);
    state.recordSubmissionOutcome("leg-1", OrderStatus.SUBMITTED, null);
    state.recordSubmissionOutcome("leg-2", OrderStatus.SUBMITTED, null);

    var view = state.toView();
    assertThat(view.status()).isEqualTo(BasketStatus.SUBMITTED);
    assertThat(view.totalLegs()).isEqualTo(2);
    assertThat(view.acceptedLegs()).isEqualTo(2);
    assertThat(view.rejectedLegs()).isZero();
    assertThat(view.targetQuantity()).isEqualTo(150);
    assertThat(view.filledQuantity()).isZero();
  }

  @Test
  void allLegsRejected_isRejected() {
    var state = newBasket();
    state.addLeg("leg-1", "AAPL", Side.BUY, 100);
    state.recordSubmissionOutcome("leg-1", OrderStatus.REJECTED, "max notional");

    var view = state.toView();
    assertThat(view.status()).isEqualTo(BasketStatus.REJECTED);
    assertThat(view.acceptedLegs()).isZero();
    assertThat(view.rejectedLegs()).isEqualTo(1);
    assertThat(view.legs().get(0).reason()).isEqualTo("max notional");
  }

  @Test
  void rejectedLegsAreExcludedFromTarget_acceptedLegFillsCompleteBasket() {
    var state = newBasket();
    state.addLeg("leg-1", "AAPL", Side.BUY, 100);
    state.addLeg("leg-2", "MSFT", Side.SELL, 50);
    state.recordSubmissionOutcome("leg-1", OrderStatus.SUBMITTED, null);
    state.recordSubmissionOutcome("leg-2", OrderStatus.REJECTED, "rejected at submission");

    state.recordFill("leg-1", 100, true);

    var view = state.toView();
    assertThat(view.status()).isEqualTo(BasketStatus.FILLED);
    assertThat(view.acceptedLegs()).isEqualTo(1);
    assertThat(view.filledLegs()).isEqualTo(1);
    assertThat(view.targetQuantity()).isEqualTo(100);
    assertThat(view.filledQuantity()).isEqualTo(100);
  }

  @Test
  void partialFill_isPartiallyFilled() {
    var state = newBasket();
    state.addLeg("leg-1", "AAPL", Side.BUY, 100);
    state.recordSubmissionOutcome("leg-1", OrderStatus.SUBMITTED, null);

    state.recordFill("leg-1", 40, false);

    var view = state.toView();
    assertThat(view.status()).isEqualTo(BasketStatus.PARTIALLY_FILLED);
    assertThat(view.filledQuantity()).isEqualTo(40);
    assertThat(view.filledLegs()).isZero();
  }

  @Test
  void fillNeverDowngradedByLateSubmissionOutcome() {
    var state = newBasket();
    state.addLeg("leg-1", "AAPL", Side.BUY, 100);
    state.recordFill("leg-1", 100, true);
    state.recordSubmissionOutcome("leg-1", OrderStatus.SUBMITTED, null);

    var view = state.toView();
    assertThat(view.status()).isEqualTo(BasketStatus.FILLED);
    assertThat(view.legs().get(0).status()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void recordFill_unknownLeg_isNoOp() {
    var state = newBasket();
    state.addLeg("leg-1", "AAPL", Side.BUY, 100);

    assertThat(state.recordFill("ghost", 10, false)).isFalse();
  }
}
