package com.mariaalpha.executionengine.basket;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.Side;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BasketRegistryTest {

  private BasketRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new BasketRegistry();
  }

  private BasketState basketWithLeg(String basketId, String legId) {
    var state = new BasketState(basketId, "test", Instant.now());
    state.addLeg(legId, "AAPL", Side.BUY, 100);
    state.recordSubmissionOutcome(legId, OrderStatus.SUBMITTED, null);
    registry.register(state);
    registry.linkLeg(legId, basketId);
    return state;
  }

  @Test
  void recordLegFill_attributesFillToOwningBasket() {
    basketWithLeg("b1", "leg-1");

    var attributed = registry.recordLegFill("leg-1", 100, true);

    assertThat(attributed).contains("b1");
    assertThat(registry.view("b1").orElseThrow().status()).isEqualTo(BasketStatus.FILLED);
    assertThat(registry.trackedBaskets()).isEqualTo(1);
  }

  @Test
  void recordLegFill_unknownLeg_returnsEmpty() {
    assertThat(registry.recordLegFill("ghost", 100, true)).isEmpty();
  }

  @Test
  void recordLegFill_completeLeg_unlinksSoLaterStrayFillsAreIgnored() {
    basketWithLeg("b1", "leg-1");

    assertThat(registry.recordLegFill("leg-1", 100, true)).contains("b1");
    // A duplicate/stray fill after completion is no longer attributed.
    assertThat(registry.recordLegFill("leg-1", 100, true)).isEmpty();
  }

  @Test
  void view_and_all_reflectRegisteredBaskets() {
    basketWithLeg("b1", "leg-1");
    basketWithLeg("b2", "leg-2");

    assertThat(registry.view("b1")).isPresent();
    assertThat(registry.view("missing")).isEmpty();
    assertThat(registry.all()).hasSize(2);
  }
}
