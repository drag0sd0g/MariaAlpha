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
    // No immediate resubmit here — we don't have a fresh reference.
  }

  public void onParentCancelRequested(Order parent) {
    cancelActiveChild(parent.getOrderId());
    transition(parent, OrderStatus.CANCELLED, "pegged-parent-cancelled");
    registry.removeParent(parent.getOrderId());
  }

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
