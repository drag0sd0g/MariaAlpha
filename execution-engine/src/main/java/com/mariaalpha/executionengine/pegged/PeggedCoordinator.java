package com.mariaalpha.executionengine.pegged;

import com.mariaalpha.executionengine.adapter.VenueAdapter;
import com.mariaalpha.executionengine.adapter.VenueAdapterRegistry;
import com.mariaalpha.executionengine.handler.LimitOrderHandler;
import com.mariaalpha.executionengine.lifecycle.IllegalStateTransitionException;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.router.SmartOrderRouter;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the lifecycle of PEGGED parent orders (roadmap 3.2.3): translates each parent into a single
 * LIMIT child at the pegged price, and as NBBO ticks arrive, cancels and resubmits a fresh child
 * when the reference moves by more than {@link PeggedConfig#repegThresholdBps()}.
 *
 * <p>Hooked into {@link com.mariaalpha.executionengine.service.OrderExecutionService} the same way
 * {@link com.mariaalpha.executionengine.iceberg.IcebergCoordinator} is — {@link #onParentSubmit}
 * replaces the normal venue dispatch for PEGGED parents, and {@link #onChildFillIfApplicable} fires
 * after each child's fill event. NBBO subscription is established at startup via {@link
 * MarketStateTracker#subscribe}.
 */
@Component
public class PeggedCoordinator {

  private static final Logger LOG = LoggerFactory.getLogger(PeggedCoordinator.class);

  private final PeggedRegistry registry;
  private final PeggedPriceCalculator priceCalculator;
  private final PeggedChildFactory childFactory;
  private final PeggedConfig config;
  private final LimitOrderHandler limitHandler;
  private final SmartOrderRouter router;
  private final VenueAdapterRegistry venueAdapters;
  private final OrderLifecycleManager lifecycleManager;
  private final MarketStateTracker marketStateTracker;
  private final PeggedMetrics metrics;

  public PeggedCoordinator(
      PeggedRegistry registry,
      PeggedPriceCalculator priceCalculator,
      PeggedChildFactory childFactory,
      PeggedConfig config,
      LimitOrderHandler limitHandler,
      SmartOrderRouter router,
      VenueAdapterRegistry venueAdapters,
      OrderLifecycleManager lifecycleManager,
      MarketStateTracker marketStateTracker,
      PeggedMetrics metrics) {
    this.registry = registry;
    this.priceCalculator = priceCalculator;
    this.childFactory = childFactory;
    this.config = config;
    this.limitHandler = limitHandler;
    this.router = router;
    this.venueAdapters = venueAdapters;
    this.lifecycleManager = lifecycleManager;
    this.marketStateTracker = marketStateTracker;
    this.metrics = metrics;
  }

  @PostConstruct
  void subscribeToMarketStateTicks() {
    marketStateTracker.subscribe(this::onMarketStateUpdate);
  }

  /**
   * Called by {@code OrderExecutionService.processOrder} when it encounters a PEGGED parent.
   * Computes the initial pegged price, submits the first LIMIT child, and transitions the parent to
   * SUBMITTED.
   *
   * @return true if the first child was accepted by its venue, false if rejected.
   */
  public boolean onParentSubmit(Order parent) {
    var marketState = marketStateTracker.getMarketState(parent.getSymbol());
    var reference =
        priceCalculator.referencePrice(parent.getPegType(), parent.getSide(), marketState);
    if (reference == null) {
      LOG.warn(
          "PEGGED parent {} rejected — no reference price available for {} (peg={})",
          parent.getOrderId(),
          parent.getSymbol(),
          parent.getPegType());
      return false;
    }
    var price =
        priceCalculator.peggedPrice(
            reference, parent.getSide(), parent.getPegOffsetBps(), parent.getLimitPrice());
    if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
      return false;
    }
    registry.recordParent(parent);
    metrics.recordParentSubmitted(parent.getSymbol(), parent.getPegType().name());
    return submitChild(parent, reference, price, false);
  }

  /**
   * Called after each child {@link ExecutionReport} is processed. No-op if the order is not a
   * pegged child.
   */
  public void onChildFillIfApplicable(Order child, ExecutionReport report) {
    if (child.getParentOrderId() == null) {
      return;
    }
    var parentOpt = registry.parentFor(child.getOrderId());
    if (parentOpt.isEmpty()) {
      return;
    }
    var parent = parentOpt.get();
    boolean childComplete = report.remainingQuantity() == 0;
    var updated =
        registry.recordChildFill(parent.getOrderId(), report.fillQuantity(), childComplete);
    if (updated == null) {
      return;
    }
    transitionParentForChildFill(parent, updated);
    if (updated.parentComplete()) {
      finalizeParentFilled(parent);
    }
    // If the child is complete but parent isn't, the next NBBO tick submits a fresh child
    // through onMarketStateUpdate (unconditionally when no child is active — see maybeRepeg).
    // No immediate resubmit here — we don't have a fresh reference.
  }

  /** Cascade a parent CANCELLED transition to the currently-active child, if any. */
  public void onParentCancelRequested(Order parent) {
    cancelActiveChild(parent.getOrderId());
    transition(parent, OrderStatus.CANCELLED, "pegged-parent-cancelled");
    registry.removeParent(parent.getOrderId());
  }

  /**
   * Listener for every {@link MarketState} update from {@link MarketStateTracker}. Walks the open
   * pegged parents for this symbol; if the recomputed pegged price has moved past {@code
   * repegThresholdBps}, cancels the active child and submits a fresh one.
   */
  void onMarketStateUpdate(MarketState state) {
    if (state == null || registry.trackedParents() == 0) {
      return;
    }
    for (var parentId : registry.trackedParentIds()) {
      var parentOpt = lookupParent(parentId);
      if (parentOpt.isEmpty()) {
        continue;
      }
      var parent = parentOpt.get();
      if (!state.symbol().equals(parent.getSymbol())) {
        continue;
      }
      maybeRepeg(parent, state);
    }
  }

  private java.util.Optional<Order> lookupParent(String parentOrderId) {
    var managed = lifecycleManager.getOrder(parentOrderId);
    return java.util.Optional.ofNullable(managed);
  }

  private void maybeRepeg(Order parent, MarketState state) {
    if (isTerminal(parent.getStatus())) {
      // Parent was rejected/cancelled/filled outside the coordinator (e.g. first child rejected
      // and OrderExecutionService transitioned the parent) — stop tracking it.
      registry.removeParent(parent.getOrderId());
      return;
    }
    var progressOpt = registry.progress(parent.getOrderId());
    if (progressOpt.isEmpty()) {
      return;
    }
    var progress = progressOpt.get();
    if (progress.parentComplete()) {
      return;
    }
    var reference = priceCalculator.referencePrice(parent.getPegType(), parent.getSide(), state);
    if (reference == null) {
      return;
    }
    var newPrice =
        priceCalculator.peggedPrice(
            reference, parent.getSide(), parent.getPegOffsetBps(), parent.getLimitPrice());
    if (newPrice == null) {
      return;
    }
    boolean hasActiveChild = progress.activeChildOrderId() != null;
    // With no active child (previous child filled completely but the parent still has remaining
    // quantity), resubmit on the next tick regardless of how little the price moved — otherwise
    // the residual would sit idle until the market moved a full repeg threshold.
    if (hasActiveChild
        && !priceCalculator.shouldRepeg(
            progress.lastSubmittedPrice(), newPrice, config.repegThresholdBps())) {
      return;
    }
    if (hasActiveChild) {
      cancelActiveChild(parent.getOrderId());
    }
    submitChild(parent, reference, newPrice, hasActiveChild);
  }

  private static boolean isTerminal(OrderStatus status) {
    return status == OrderStatus.FILLED
        || status == OrderStatus.CANCELLED
        || status == OrderStatus.REJECTED;
  }

  private boolean submitChild(
      Order parent, BigDecimal reference, BigDecimal limitPrice, boolean isRepeg) {
    // Remaining quantity lives in the registry's PeggedProgress — child fills are applied to the
    // child orders, never to the parent Order object, so parent.getFilledQuantity() is always 0.
    var remaining =
        registry.progress(parent.getOrderId()).map(PeggedProgress::remainingQuantity).orElse(0);
    if (remaining <= 0) {
      return false;
    }
    var child = childFactory.createChild(parent, remaining, limitPrice);
    var routingDecision = router.route(child);
    var adapterOpt = venueAdapters.get(routingDecision.venue());
    if (adapterOpt.isEmpty()) {
      LOG.error(
          "No venue adapter for {} when submitting pegged child of {}",
          routingDecision.venue(),
          parent.getOrderId());
      return false;
    }
    VenueAdapter adapter = adapterOpt.get();
    child.setVenue(adapter.venueName());
    lifecycleManager.registerOrder(child);
    var instruction = limitHandler.toExecutionInstruction(child);
    var ack = adapter.submitOrder(instruction);
    if (!ack.accepted()) {
      LOG.warn(
          "Pegged child {} rejected by venue {}: {}",
          child.getOrderId(),
          adapter.venueName(),
          ack.reason());
      transition(child, OrderStatus.REJECTED, ack.reason());
      return false;
    }
    child.setExchangeOrderId(ack.exchangeOrderId());
    lifecycleManager.registerExchangeOrderId(ack.exchangeOrderId(), child.getOrderId());
    registry.recordChildSubmitted(
        parent.getOrderId(), child.getOrderId(), reference, limitPrice, isRepeg);
    transition(child, OrderStatus.SUBMITTED, null);
    if (parent.getStatus() == OrderStatus.NEW) {
      transition(parent, OrderStatus.SUBMITTED, null);
    }
    metrics.recordChildSubmitted(parent.getSymbol(), parent.getPegType().name());
    if (isRepeg) {
      metrics.recordRepeg(parent.getSymbol(), parent.getPegType().name());
    }
    return true;
  }

  private void cancelActiveChild(String parentOrderId) {
    registry
        .activeChildFor(parentOrderId)
        .ifPresent(
            activeChildId -> {
              var child = lifecycleManager.getOrder(activeChildId);
              if (child != null && child.getExchangeOrderId() != null) {
                venueAdapters
                    .get(child.getVenue())
                    .ifPresent(a -> a.cancelOrder(child.getExchangeOrderId()));
              }
              registry.recordChildCancelled(parentOrderId, activeChildId);
            });
  }

  private void transitionParentForChildFill(Order parent, PeggedProgress progress) {
    if (progress.parentComplete()) {
      return;
    }
    if (parent.getStatus() != OrderStatus.PARTIALLY_FILLED) {
      transition(parent, OrderStatus.PARTIALLY_FILLED, null);
    }
  }

  private void finalizeParentFilled(Order parent) {
    transition(parent, OrderStatus.FILLED, "pegged-complete");
    metrics.recordParentFilled(parent.getSymbol(), parent.getPegType().name());
    registry.removeParent(parent.getOrderId());
  }

  private void transition(Order order, OrderStatus newStatus, String reason) {
    try {
      lifecycleManager.transition(order.getOrderId(), newStatus, null, reason);
    } catch (IllegalStateTransitionException e) {
      LOG.debug("Skipping pegged transition for {}: {}", order.getOrderId(), e.getMessage());
    }
  }
}
