package com.mariaalpha.executionengine.pegged;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PeggedRegistryTest {

  private final PeggedRegistry registry = new PeggedRegistry();

  private static Order parent() {
    return new Order(
        new OrderSignal(
            "AAPL",
            Side.BUY,
            100,
            OrderType.PEGGED,
            null,
            null,
            "MANUAL",
            Instant.EPOCH,
            null,
            null,
            null,
            PegType.MIDPOINT,
            0));
  }

  @Test
  void recordParentSeedsProgress() {
    var p = parent();
    registry.recordParent(p);
    var progress = registry.progress(p.getOrderId()).orElseThrow();
    assertThat(progress.totalQuantity()).isEqualTo(100);
    assertThat(progress.filledQuantity()).isZero();
    assertThat(progress.activeChildOrderId()).isNull();
    assertThat(registry.trackedParents()).isEqualTo(1);
  }

  @Test
  void recordChildSubmittedTracksActiveChild() {
    var p = parent();
    registry.recordParent(p);
    registry.recordChildSubmitted(
        p.getOrderId(), "child-1", new BigDecimal("100.10"), new BigDecimal("100.11"), false);
    var progress = registry.progress(p.getOrderId()).orElseThrow();
    assertThat(progress.activeChildOrderId()).isEqualTo("child-1");
    assertThat(progress.lastReferencePrice()).isEqualByComparingTo("100.10");
    assertThat(progress.lastSubmittedPrice()).isEqualByComparingTo("100.11");
    assertThat(progress.repegsTotal()).isZero();
    assertThat(registry.parentFor("child-1").orElseThrow().getOrderId()).isEqualTo(p.getOrderId());
  }

  @Test
  void repegIncrementsCounter() {
    var p = parent();
    registry.recordParent(p);
    registry.recordChildSubmitted(
        p.getOrderId(), "child-1", new BigDecimal("100.10"), new BigDecimal("100.11"), false);
    registry.recordChildCancelled(p.getOrderId(), "child-1");
    registry.recordChildSubmitted(
        p.getOrderId(), "child-2", new BigDecimal("100.20"), new BigDecimal("100.21"), true);
    assertThat(registry.progress(p.getOrderId()).orElseThrow().repegsTotal()).isEqualTo(1);
  }

  @Test
  void childFillAdvancesFilledQuantity() {
    var p = parent();
    registry.recordParent(p);
    registry.recordChildSubmitted(
        p.getOrderId(), "child-1", new BigDecimal("100.10"), new BigDecimal("100.11"), false);
    var afterPartial = registry.recordChildFill(p.getOrderId(), 40, false);
    assertThat(afterPartial.filledQuantity()).isEqualTo(40);
    assertThat(afterPartial.activeChildOrderId()).isEqualTo("child-1");
    assertThat(afterPartial.parentComplete()).isFalse();

    var afterFull = registry.recordChildFill(p.getOrderId(), 60, true);
    assertThat(afterFull.filledQuantity()).isEqualTo(100);
    assertThat(afterFull.parentComplete()).isTrue();
    assertThat(afterFull.activeChildOrderId()).isNull();
  }

  @Test
  void removeParentClearsEverything() {
    var p = parent();
    registry.recordParent(p);
    registry.recordChildSubmitted(
        p.getOrderId(), "child-1", new BigDecimal("100.10"), new BigDecimal("100.11"), false);
    registry.removeParent(p.getOrderId());
    assertThat(registry.progress(p.getOrderId())).isEmpty();
    assertThat(registry.parentFor("child-1")).isEmpty();
    assertThat(registry.trackedParents()).isZero();
  }
}
