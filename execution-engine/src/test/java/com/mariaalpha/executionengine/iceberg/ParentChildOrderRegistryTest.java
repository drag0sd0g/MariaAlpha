package com.mariaalpha.executionengine.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParentChildOrderRegistryTest {

  private ParentChildOrderRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new ParentChildOrderRegistry();
  }

  @Test
  void recordParent_initialisesProgressToZeroes() {
    var parent = createParent(6000, 1000);
    registry.recordParent(parent, 1000);

    var progress = registry.progress(parent.getOrderId()).orElseThrow();
    assertThat(progress.totalQuantity()).isEqualTo(6000);
    assertThat(progress.displayQuantity()).isEqualTo(1000);
    assertThat(progress.submittedQuantity()).isZero();
    assertThat(progress.filledQuantity()).isZero();
    assertThat(progress.slicesSubmitted()).isZero();
    assertThat(progress.activeChildOrderId()).isNull();
    assertThat(registry.trackedParents()).isEqualTo(1);
  }

  @Test
  void linkChildToParent_tracksReverseMapAndUpdatesProgress() {
    var parent = createParent(6000, 1000);
    registry.recordParent(parent, 1000);
    var child = createChild(parent, 1000);

    registry.linkChildToParent(child, parent, 1000);

    assertThat(registry.parentFor(child.getOrderId())).contains(parent);
    var progress = registry.progress(parent.getOrderId()).orElseThrow();
    assertThat(progress.submittedQuantity()).isEqualTo(1000);
    assertThat(progress.slicesSubmitted()).isEqualTo(1);
    assertThat(progress.activeChildOrderId()).isEqualTo(child.getOrderId());
  }

  @Test
  void recordChildFill_updatesFilledQuantityAndClearsActiveWhenComplete() {
    var parent = createParent(6000, 1000);
    registry.recordParent(parent, 1000);
    var child = createChild(parent, 1000);
    registry.linkChildToParent(child, parent, 1000);

    var afterFill = registry.recordChildFill(parent.getOrderId(), 1000, true);

    assertThat(afterFill.filledQuantity()).isEqualTo(1000);
    assertThat(afterFill.activeChildOrderId()).isNull();
  }

  @Test
  void recordChildFill_partialFillKeepsActiveChild() {
    var parent = createParent(6000, 1000);
    registry.recordParent(parent, 1000);
    var child = createChild(parent, 1000);
    registry.linkChildToParent(child, parent, 1000);

    var afterFill = registry.recordChildFill(parent.getOrderId(), 600, false);

    assertThat(afterFill.filledQuantity()).isEqualTo(600);
    assertThat(afterFill.activeChildOrderId()).isEqualTo(child.getOrderId());
  }

  @Test
  void activeChildFor_returnsEmptyWhenNoActiveChild() {
    var parent = createParent(6000, 1000);
    registry.recordParent(parent, 1000);

    assertThat(registry.activeChildFor(parent.getOrderId())).isEmpty();
  }

  @Test
  void removeParent_clearsAllRelatedEntries() {
    var parent = createParent(6000, 1000);
    registry.recordParent(parent, 1000);
    var child = createChild(parent, 1000);
    registry.linkChildToParent(child, parent, 1000);

    registry.removeParent(parent.getOrderId());

    assertThat(registry.progress(parent.getOrderId())).isEmpty();
    assertThat(registry.parentFor(child.getOrderId())).isEmpty();
    assertThat(registry.trackedParents()).isZero();
  }

  private Order createParent(int qty, int displayQty) {
    var signal =
        new OrderSignal(
            "AAPL",
            Side.BUY,
            qty,
            OrderType.ICEBERG,
            new BigDecimal("150.00"),
            null,
            "MANUAL",
            Instant.now(),
            displayQty,
            TimeInForce.DAY,
            null);
    return new Order(signal);
  }

  private Order createChild(Order parent, int sliceQty) {
    var signal =
        new OrderSignal(
            parent.getSymbol(),
            parent.getSide(),
            sliceQty,
            OrderType.LIMIT,
            parent.getLimitPrice(),
            null,
            "ICEBERG-CHILD",
            Instant.now(),
            null,
            TimeInForce.DAY,
            parent.getOrderId());
    return new Order(signal);
  }
}
