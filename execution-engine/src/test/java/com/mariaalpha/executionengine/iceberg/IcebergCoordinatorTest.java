package com.mariaalpha.executionengine.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.adapter.VenueAdapter;
import com.mariaalpha.executionengine.adapter.VenueAdapterRegistry;
import com.mariaalpha.executionengine.handler.LimitOrderHandler;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderAck;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.RoutingDecision;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import com.mariaalpha.executionengine.router.SmartOrderRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IcebergCoordinatorTest {

  private ParentChildOrderRegistry registry;
  private IcebergSliceFactory sliceFactory;
  private LimitOrderHandler limitHandler;
  private SmartOrderRouter router;
  private VenueAdapterRegistry venueAdapters;
  private VenueAdapter venueAdapter;
  private OrderLifecycleManager lifecycleManager;
  private IcebergMetrics metrics;
  private IcebergCoordinator coordinator;
  private List<Order> capturedChildren;
  private int submitCounter;

  @BeforeEach
  void setUp() {
    registry = new ParentChildOrderRegistry();
    sliceFactory = new IcebergSliceFactory();
    limitHandler = new LimitOrderHandler();
    router = mock(SmartOrderRouter.class);
    venueAdapters = mock(VenueAdapterRegistry.class);
    venueAdapter = mock(VenueAdapter.class);
    lifecycleManager = mock(OrderLifecycleManager.class);
    metrics = new IcebergMetrics(new SimpleMeterRegistry());
    capturedChildren = new ArrayList<>();
    submitCounter = 0;

    when(venueAdapter.venueName()).thenReturn("PRIMARY");
    when(venueAdapters.get("PRIMARY")).thenReturn(Optional.of(venueAdapter));
    when(router.route(any()))
        .thenAnswer(
            inv -> {
              Order o = inv.getArgument(0);
              capturedChildren.add(o);
              return RoutingDecision.legacy(
                  o.getOrderId(), "PRIMARY", "DirectRouter", Instant.now());
            });
    when(venueAdapter.submitOrder(any()))
        .thenAnswer(
            inv -> {
              submitCounter++;
              return new OrderAck("child", "EX-" + submitCounter, true, "");
            });

    coordinator =
        new IcebergCoordinator(
            registry, sliceFactory, limitHandler, router, venueAdapters, lifecycleManager, metrics);
  }

  @Test
  void onParentSubmit_registersParentAndSubmitsFirstChild() {
    var parent = createParent(6000, 1000);
    boolean accepted = coordinator.onParentSubmit(parent);

    assertThat(accepted).isTrue();
    var progress = registry.progress(parent.getOrderId()).orElseThrow();
    assertThat(progress.slicesSubmitted()).isEqualTo(1);
    assertThat(progress.submittedQuantity()).isEqualTo(1000);
    verify(venueAdapter).submitOrder(any(ExecutionInstruction.class));
  }

  @Test
  void onParentSubmit_transitionsParentToSubmittedOnFirstChildAck() {
    var parent = createParent(6000, 1000);
    coordinator.onParentSubmit(parent);

    verify(lifecycleManager)
        .transition(eq(parent.getOrderId()), eq(OrderStatus.SUBMITTED), eq(null), eq(null));
  }

  @Test
  void onParentSubmit_returnsFalseAndRejectsParentOnFirstChildVenueReject() {
    when(venueAdapter.submitOrder(any()))
        .thenReturn(new OrderAck("child", "", false, "Insufficient buying power"));
    var parent = createParent(6000, 1000);

    boolean accepted = coordinator.onParentSubmit(parent);

    assertThat(accepted).isFalse();
    verify(lifecycleManager)
        .transition(any(), eq(OrderStatus.REJECTED), eq(null), eq("Insufficient buying power"));
  }

  @Test
  void onParentSubmit_throwsWhenDisplayQuantityMissing() {
    var signal =
        new OrderSignal(
            "AAPL",
            Side.BUY,
            6000,
            OrderType.ICEBERG,
            new BigDecimal("150.00"),
            null,
            "MANUAL",
            Instant.now());
    var parent = new Order(signal);

    try {
      coordinator.onParentSubmit(parent);
      assertThat(false).as("expected exception").isTrue();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).contains("no displayQuantity");
    }
  }

  @Test
  void onChildFill_submitsNextSliceWhenChildComplete() {
    var parent = createParent(6000, 1000);
    coordinator.onParentSubmit(parent);
    var firstChild = capturedChildren.get(0);

    var report =
        new ExecutionReport(
            "EX-1", new BigDecimal("150.00"), 1000, 0, "PRIMARY", Instant.now(), null);
    coordinator.onChildFillIfApplicable(firstChild, report);

    var progress = registry.progress(parent.getOrderId()).orElseThrow();
    assertThat(progress.filledQuantity()).isEqualTo(1000);
    assertThat(progress.slicesSubmitted()).isEqualTo(2);
  }

  @Test
  void onChildFill_doesNotSubmitWhenChildPartial() {
    var parent = createParent(6000, 1000);
    coordinator.onParentSubmit(parent);
    var firstChild = capturedChildren.get(0);

    var partial =
        new ExecutionReport(
            "EX-1", new BigDecimal("150.00"), 400, 600, "PRIMARY", Instant.now(), null);
    coordinator.onChildFillIfApplicable(firstChild, partial);

    var progress = registry.progress(parent.getOrderId()).orElseThrow();
    assertThat(progress.filledQuantity()).isEqualTo(400);
    assertThat(progress.slicesSubmitted()).isEqualTo(1);
  }

  @Test
  void onChildFill_transitionsParentToPartiallyFilledOnFirstChildComplete() {
    var parent = createParent(6000, 1000);
    coordinator.onParentSubmit(parent);
    var firstChild = capturedChildren.get(0);

    var report =
        new ExecutionReport(
            "EX-1", new BigDecimal("150.00"), 1000, 0, "PRIMARY", Instant.now(), null);
    coordinator.onChildFillIfApplicable(firstChild, report);

    verify(lifecycleManager)
        .transition(eq(parent.getOrderId()), eq(OrderStatus.PARTIALLY_FILLED), eq(null), eq(null));
  }

  @Test
  void onChildFill_completesParentOnFinalSlice() {
    var parent = createParent(2000, 1000);
    coordinator.onParentSubmit(parent);

    var firstChild = capturedChildren.get(0);
    coordinator.onChildFillIfApplicable(
        firstChild,
        new ExecutionReport(
            "EX-1", new BigDecimal("150.00"), 1000, 0, "PRIMARY", Instant.now(), null));

    var secondChild = capturedChildren.get(1);
    coordinator.onChildFillIfApplicable(
        secondChild,
        new ExecutionReport(
            "EX-2", new BigDecimal("150.00"), 1000, 0, "PRIMARY", Instant.now(), null));

    verify(lifecycleManager)
        .transition(
            eq(parent.getOrderId()), eq(OrderStatus.FILLED), eq(null), eq("iceberg-complete"));
  }

  @Test
  void onChildFill_finalSliceSizedRemainder() {
    var parent = createParent(2500, 1000);
    coordinator.onParentSubmit(parent);

    coordinator.onChildFillIfApplicable(
        capturedChildren.get(0),
        new ExecutionReport(
            "EX-1", new BigDecimal("150.00"), 1000, 0, "PRIMARY", Instant.now(), null));
    coordinator.onChildFillIfApplicable(
        capturedChildren.get(1),
        new ExecutionReport(
            "EX-2", new BigDecimal("150.00"), 1000, 0, "PRIMARY", Instant.now(), null));

    assertThat(capturedChildren).hasSize(3);
    assertThat(capturedChildren.get(2).getQuantity()).isEqualTo(500);
  }

  @Test
  void onChildFill_noOpForNonIcebergChild() {
    var signal =
        new OrderSignal(
            "AAPL",
            Side.BUY,
            100,
            OrderType.LIMIT,
            new BigDecimal("150.00"),
            null,
            "VWAP",
            Instant.now());
    var nonIcebergChild = new Order(signal);
    var report =
        new ExecutionReport(
            "EX-X", new BigDecimal("150.00"), 100, 0, "PRIMARY", Instant.now(), null);

    coordinator.onChildFillIfApplicable(nonIcebergChild, report);

    verify(lifecycleManager, never()).transition(any(), any(), any(), any());
  }

  @Test
  void onParentCancelRequested_cancelsActiveChildAtVenueAndTransitionsParent() {
    var parent = createParent(6000, 1000);
    coordinator.onParentSubmit(parent);
    var firstChild = capturedChildren.get(0);
    firstChild.setExchangeOrderId("EX-1");
    firstChild.setVenue("PRIMARY");
    when(lifecycleManager.getOrder(firstChild.getOrderId())).thenReturn(firstChild);

    coordinator.onParentCancelRequested(parent);

    verify(venueAdapter).cancelOrder("EX-1");
    var statusCaptor = ArgumentCaptor.forClass(OrderStatus.class);
    verify(lifecycleManager, atLeastOnce())
        .transition(eq(parent.getOrderId()), statusCaptor.capture(), eq(null), any());
    assertThat(statusCaptor.getAllValues()).contains(OrderStatus.CANCELLED);
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
}
