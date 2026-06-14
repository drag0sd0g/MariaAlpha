package com.mariaalpha.executionengine.pegged;

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
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderAck;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.RoutingDecision;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.router.SmartOrderRouter;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PeggedCoordinatorTest {

  private static final PeggedConfig CONFIG = new PeggedConfig(5, 100);

  private PeggedRegistry registry;
  private PeggedPriceCalculator priceCalculator;
  private PeggedChildFactory childFactory;
  private LimitOrderHandler limitHandler;
  private SmartOrderRouter router;
  private VenueAdapterRegistry venueAdapters;
  private VenueAdapter venueAdapter;
  private OrderLifecycleManager lifecycleManager;
  private MarketStateTracker marketStateTracker;
  private PeggedMetrics metrics;
  private PeggedCoordinator coordinator;
  private List<Order> capturedChildren;
  private int submitCounter;

  @BeforeEach
  void setUp() {
    registry = new PeggedRegistry();
    priceCalculator = new PeggedPriceCalculator();
    childFactory = new PeggedChildFactory();
    limitHandler = new LimitOrderHandler();
    router = mock(SmartOrderRouter.class);
    venueAdapters = mock(VenueAdapterRegistry.class);
    venueAdapter = mock(VenueAdapter.class);
    lifecycleManager = mock(OrderLifecycleManager.class);
    marketStateTracker = new MarketStateTracker();
    metrics = new PeggedMetrics(new SimpleMeterRegistry());
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
        new PeggedCoordinator(
            registry,
            priceCalculator,
            childFactory,
            CONFIG,
            limitHandler,
            router,
            venueAdapters,
            lifecycleManager,
            marketStateTracker,
            metrics);
    // @PostConstruct doesn't fire under plain unit tests — wire the listener manually.
    coordinator.subscribeToMarketStateTicks();
  }

  private Order parent(Side side, int quantity, PegType pegType, Integer offsetBps) {
    return new Order(
        new OrderSignal(
            "AAPL",
            side,
            quantity,
            OrderType.PEGGED,
            null,
            null,
            "MANUAL",
            Instant.EPOCH,
            null,
            null,
            null,
            pegType,
            offsetBps));
  }

  private void primeBook(String bid, String ask) {
    marketStateTracker.update(
        new MarketState(
            "AAPL", new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(bid), Instant.now()));
  }

  @Test
  void onParentSubmitWithoutMarketDataRejects() {
    var p = parent(Side.BUY, 100, PegType.MIDPOINT, 0);
    boolean accepted = coordinator.onParentSubmit(p);
    assertThat(accepted).isFalse();
    assertThat(registry.trackedParents()).isZero();
  }

  @Test
  void onParentSubmitWithMidpointSubmitsLimitChildAtMidpoint() {
    primeBook("100.00", "100.20"); // mid 100.10
    var p = parent(Side.BUY, 100, PegType.MIDPOINT, 0);
    when(lifecycleManager.getOrder(p.getOrderId())).thenReturn(p);

    boolean accepted = coordinator.onParentSubmit(p);

    assertThat(accepted).isTrue();
    assertThat(capturedChildren).hasSize(1);
    var child = capturedChildren.get(0);
    assertThat(child.getOrderType()).isEqualTo(OrderType.LIMIT);
    assertThat(child.getLimitPrice().doubleValue()).isEqualTo(100.10);
    assertThat(child.getParentOrderId()).isEqualTo(p.getOrderId());
    var progress = registry.progress(p.getOrderId()).orElseThrow();
    assertThat(progress.activeChildOrderId()).isEqualTo(child.getOrderId());
    assertThat(progress.lastReferencePrice().doubleValue()).isEqualTo(100.10);
    verify(lifecycleManager)
        .transition(eq(p.getOrderId()), eq(OrderStatus.SUBMITTED), eq(null), eq(null));
  }

  @Test
  void primaryBuyPegsToBid() {
    primeBook("100.00", "100.20");
    var p = parent(Side.BUY, 100, PegType.PRIMARY, 0);
    when(lifecycleManager.getOrder(p.getOrderId())).thenReturn(p);
    coordinator.onParentSubmit(p);
    assertThat(capturedChildren.get(0).getLimitPrice()).isEqualByComparingTo("100.00");
  }

  @Test
  void marketBuyPegsToAsk() {
    primeBook("100.00", "100.20");
    var p = parent(Side.BUY, 100, PegType.MARKET, 0);
    when(lifecycleManager.getOrder(p.getOrderId())).thenReturn(p);
    coordinator.onParentSubmit(p);
    assertThat(capturedChildren.get(0).getLimitPrice()).isEqualByComparingTo("100.20");
  }

  @Test
  void nbboMoveBelowThresholdDoesNotRepeg() {
    primeBook("100.00", "100.20"); // mid 100.10
    var p = parent(Side.BUY, 100, PegType.MIDPOINT, 0);
    when(lifecycleManager.getOrder(p.getOrderId())).thenReturn(p);
    coordinator.onParentSubmit(p);
    assertThat(capturedChildren).hasSize(1);

    // small wobble — mid moves from 100.10 to 100.11 = ~1 bps, below 5 bps threshold
    primeBook("100.01", "100.21");
    assertThat(capturedChildren).hasSize(1);
  }

  @Test
  void nbboMoveAboveThresholdRepegs() {
    primeBook("100.00", "100.20"); // mid 100.10
    var p = parent(Side.BUY, 100, PegType.MIDPOINT, 0);
    when(lifecycleManager.getOrder(p.getOrderId())).thenReturn(p);
    when(lifecycleManager.getOrder(any()))
        .thenAnswer(
            inv -> {
              String id = inv.getArgument(0);
              if (id.equals(p.getOrderId())) {
                return p;
              }
              // Return a real child Order so cancelActiveChild can read exchangeOrderId
              var match =
                  capturedChildren.stream().filter(c -> c.getOrderId().equals(id)).findFirst();
              if (match.isPresent()) {
                match.get().setExchangeOrderId("EX-existing");
                return match.get();
              }
              return null;
            });

    coordinator.onParentSubmit(p);
    int firstSubmitCount = submitCounter;
    assertThat(capturedChildren).hasSize(1);

    // mid jumps from 100.10 to 100.30 = ~20 bps move on the submitted price → triggers repeg
    primeBook("100.20", "100.40");

    assertThat(capturedChildren.size()).isGreaterThanOrEqualTo(2);
    assertThat(submitCounter).isGreaterThan(firstSubmitCount);
    var last = capturedChildren.get(capturedChildren.size() - 1);
    assertThat(last.getLimitPrice().doubleValue()).isEqualTo(100.30);
    var progress = registry.progress(p.getOrderId()).orElseThrow();
    assertThat(progress.repegsTotal()).isEqualTo(1);
    verify(venueAdapter, atLeastOnce()).cancelOrder(any());
  }

  @Test
  void repegAfterPartialFillSubmitsOnlyRemainingQuantity() {
    primeBook("100.00", "100.20"); // mid 100.10
    var p = parent(Side.BUY, 100, PegType.MIDPOINT, 0);
    when(lifecycleManager.getOrder(any()))
        .thenAnswer(
            inv -> {
              String id = inv.getArgument(0);
              if (id.equals(p.getOrderId())) {
                return p;
              }
              var match =
                  capturedChildren.stream().filter(c -> c.getOrderId().equals(id)).findFirst();
              match.ifPresent(c -> c.setExchangeOrderId("EX-existing"));
              return match.orElse(null);
            });
    coordinator.onParentSubmit(p);
    var child = capturedChildren.get(0);

    // Child partially fills 40 of 100, stays working.
    coordinator.onChildFillIfApplicable(
        child,
        new ExecutionReport(
            "EX-1", new BigDecimal("100.10"), 40, 60, "PRIMARY", Instant.now(), null));

    // Big NBBO move triggers a repeg — the fresh child must be sized at the REMAINING 60,
    // not the full parent quantity (fills live on the children, never the parent Order).
    primeBook("100.20", "100.40");

    assertThat(capturedChildren).hasSize(2);
    assertThat(capturedChildren.get(1).getQuantity()).isEqualTo(60);
  }

  @Test
  void completedChildWithRemainingParentResubmitsOnNextTickWithoutThreshold() {
    primeBook("100.00", "100.20"); // mid 100.10
    var p = parent(Side.BUY, 100, PegType.MIDPOINT, 0);
    when(lifecycleManager.getOrder(any()))
        .thenAnswer(
            inv -> {
              String id = inv.getArgument(0);
              if (id.equals(p.getOrderId())) {
                return p;
              }
              var match =
                  capturedChildren.stream().filter(c -> c.getOrderId().equals(id)).findFirst();
              return match.orElse(null);
            });
    coordinator.onParentSubmit(p);
    var child = capturedChildren.get(0);

    // Child completes (remaining 0) after filling only 40 — parent still has 60 outstanding.
    coordinator.onChildFillIfApplicable(
        child,
        new ExecutionReport(
            "EX-1", new BigDecimal("100.10"), 40, 0, "PRIMARY", Instant.now(), null));

    // A sub-threshold wobble must still spawn a fresh child for the residual — there is no
    // active child to leave working, so waiting for a full repeg move would stall the parent.
    primeBook("100.01", "100.21");

    assertThat(capturedChildren).hasSize(2);
    assertThat(capturedChildren.get(1).getQuantity()).isEqualTo(60);
    // No active child existed, so nothing should have been cancelled at the venue.
    verify(venueAdapter, never()).cancelOrder(any());
  }

  @Test
  void completedParentDoesNotRepeg() {
    primeBook("100.00", "100.20");
    var p = parent(Side.BUY, 100, PegType.MIDPOINT, 0);
    when(lifecycleManager.getOrder(p.getOrderId())).thenReturn(p);
    coordinator.onParentSubmit(p);
    var child = capturedChildren.get(0);

    // Full fill on the child
    coordinator.onChildFillIfApplicable(
        child,
        new ExecutionReport(
            "EX-1", new BigDecimal("100.10"), 100, 0, "PRIMARY", Instant.now(), null));

    int childrenBefore = capturedChildren.size();
    primeBook("105.00", "105.20"); // huge move
    assertThat(capturedChildren).hasSize(childrenBefore);
    verify(lifecycleManager)
        .transition(eq(p.getOrderId()), eq(OrderStatus.FILLED), eq(null), eq("pegged-complete"));
  }

  @Test
  void partialFillTransitionsParentToPartiallyFilled() {
    primeBook("100.00", "100.20");
    var p = parent(Side.BUY, 100, PegType.MIDPOINT, 0);
    when(lifecycleManager.getOrder(p.getOrderId())).thenReturn(p);
    coordinator.onParentSubmit(p);
    var child = capturedChildren.get(0);

    coordinator.onChildFillIfApplicable(
        child,
        new ExecutionReport(
            "EX-1", new BigDecimal("100.10"), 40, 60, "PRIMARY", Instant.now(), null));

    verify(lifecycleManager)
        .transition(eq(p.getOrderId()), eq(OrderStatus.PARTIALLY_FILLED), eq(null), eq(null));
    assertThat(registry.progress(p.getOrderId()).orElseThrow().filledQuantity()).isEqualTo(40);
  }

  @Test
  void cancelCascadesToActiveChild() {
    primeBook("100.00", "100.20");
    var p = parent(Side.BUY, 100, PegType.MIDPOINT, 0);
    when(lifecycleManager.getOrder(p.getOrderId())).thenReturn(p);
    coordinator.onParentSubmit(p);
    var child = capturedChildren.get(0);
    child.setExchangeOrderId("EX-1");
    when(lifecycleManager.getOrder(child.getOrderId())).thenReturn(child);

    coordinator.onParentCancelRequested(p);

    verify(venueAdapter).cancelOrder("EX-1");
    verify(lifecycleManager)
        .transition(
            eq(p.getOrderId()), eq(OrderStatus.CANCELLED), eq(null), eq("pegged-parent-cancelled"));
    assertThat(registry.trackedParents()).isZero();
  }

  @Test
  void marketUpdateForUnrelatedSymbolIsIgnored() {
    primeBook("100.00", "100.20");
    var p = parent(Side.BUY, 100, PegType.MIDPOINT, 0);
    when(lifecycleManager.getOrder(p.getOrderId())).thenReturn(p);
    coordinator.onParentSubmit(p);
    assertThat(capturedChildren).hasSize(1);

    // Update an unrelated symbol — even if mid is wildly different, the AAPL pegged order is
    // untouched.
    marketStateTracker.update(
        new MarketState(
            "MSFT",
            new BigDecimal("450.00"),
            new BigDecimal("450.20"),
            new BigDecimal("450.00"),
            Instant.now()));

    assertThat(capturedChildren).hasSize(1);
  }

  @Test
  void onChildFillNoOpForNonPeggedChild() {
    var nonPegged =
        new Order(
            new OrderSignal(
                "AAPL",
                Side.BUY,
                100,
                OrderType.LIMIT,
                new BigDecimal("100.00"),
                null,
                "VWAP",
                Instant.now()));
    coordinator.onChildFillIfApplicable(
        nonPegged,
        new ExecutionReport(
            "EX-X", new BigDecimal("100.00"), 100, 0, "PRIMARY", Instant.now(), null));

    verify(lifecycleManager, never()).transition(any(), any(), any(), any());
  }
}
