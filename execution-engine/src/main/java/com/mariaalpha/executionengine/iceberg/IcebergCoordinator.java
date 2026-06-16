package com.mariaalpha.executionengine.iceberg;

import com.mariaalpha.executionengine.adapter.VenueAdapter;
import com.mariaalpha.executionengine.adapter.VenueAdapterRegistry;
import com.mariaalpha.executionengine.handler.LimitOrderHandler;
import com.mariaalpha.executionengine.lifecycle.IllegalStateTransitionException;
import com.mariaalpha.executionengine.lifecycle.OrderLifecycleManager;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.router.SmartOrderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class IcebergCoordinator {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergCoordinator.class);

  private final ParentChildOrderRegistry registry;
  private final IcebergSliceFactory sliceFactory;
  private final LimitOrderHandler limitHandler;
  private final SmartOrderRouter router;
  private final VenueAdapterRegistry venueAdapters;
  private final OrderLifecycleManager lifecycleManager;
  private final IcebergMetrics metrics;

  public IcebergCoordinator(
      ParentChildOrderRegistry registry,
      IcebergSliceFactory sliceFactory,
      LimitOrderHandler limitHandler,
      SmartOrderRouter router,
      VenueAdapterRegistry venueAdapters,
      OrderLifecycleManager lifecycleManager,
      IcebergMetrics metrics) {
    this.registry = registry;
    this.sliceFactory = sliceFactory;
    this.limitHandler = limitHandler;
    this.router = router;
    this.venueAdapters = venueAdapters;
    this.lifecycleManager = lifecycleManager;
    this.metrics = metrics;
  }

  public boolean onParentSubmit(Order parent) {
    if (parent.getDisplayQuantity() == null) {
      throw new IllegalArgumentException(
          "Iceberg parent " + parent.getOrderId() + " has no displayQuantity");
    }
    registry.recordParent(parent, parent.getDisplayQuantity());
    metrics.recordParentSubmitted(parent.getSymbol());
    return submitNextSlice(parent);
  }

  public void onChildFillIfApplicable(Order child, ExecutionReport report) {
    if (child.getParentOrderId() == null) {
      return;
    }
    var parentOpt = registry.parentFor(child.getOrderId());
    if (parentOpt.isEmpty()) {
      LOG.warn(
          "Iceberg child {} fill received but parent {} not tracked",
          child.getOrderId(),
          child.getParentOrderId());
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
      return;
    }
    if (childComplete) {
      submitNextSlice(parent);
    }
  }

  public void onParentCancelRequested(Order parent) {
    registry
        .activeChildFor(parent.getOrderId())
        .ifPresent(
            activeChildId -> {
              var child = lifecycleManager.getOrder(activeChildId);
              if (child != null && child.getExchangeOrderId() != null) {
                venueAdapters
                    .get(child.getVenue())
                    .ifPresent(a -> a.cancelOrder(child.getExchangeOrderId()));
              }
            });
    transition(parent, OrderStatus.CANCELLED, "iceberg-parent-cancelled");
    registry.removeParent(parent.getOrderId());
  }

  private boolean submitNextSlice(Order parent) {
    var progressOpt = registry.progress(parent.getOrderId());
    if (progressOpt.isEmpty()) {
      return false;
    }
    var progress = progressOpt.get();
    var remaining = progress.remainingToSubmit();
    if (remaining <= 0) {
      return false;
    }
    var sliceQty = Math.min(remaining, progress.displayQuantity());
    var sliceIndex = progress.slicesSubmitted();
    var child = sliceFactory.createChild(parent, sliceQty, sliceIndex);
    var routingDecision = router.route(child);
    var adapterOpt = venueAdapters.get(routingDecision.venue());
    if (adapterOpt.isEmpty()) {
      LOG.error(
          "No venue adapter for {} when submitting iceberg child of {}",
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
          "Iceberg child {} rejected by venue {}: {}",
          child.getOrderId(),
          adapter.venueName(),
          ack.reason());
      transition(child, OrderStatus.REJECTED, ack.reason());
      return false;
    }
    child.setExchangeOrderId(ack.exchangeOrderId());
    lifecycleManager.registerExchangeOrderId(ack.exchangeOrderId(), child.getOrderId());
    registry.linkChildToParent(child, parent, sliceQty);
    transition(child, OrderStatus.SUBMITTED, null);
    if (sliceIndex == 0) {
      transition(parent, OrderStatus.SUBMITTED, null);
    }
    metrics.recordSliceSubmitted(parent.getSymbol(), parent.getSide().name());
    return true;
  }

  private void transitionParentForChildFill(Order parent, IcebergProgress progress) {
    if (progress.parentComplete()) {
      return;
    }
    if (parent.getStatus() != OrderStatus.PARTIALLY_FILLED) {
      transition(parent, OrderStatus.PARTIALLY_FILLED, null);
    }
  }

  private void finalizeParentFilled(Order parent) {
    transition(parent, OrderStatus.FILLED, "iceberg-complete");
    metrics.recordParentFilled(parent.getSymbol());
    registry.removeParent(parent.getOrderId());
  }

  private void transition(Order order, OrderStatus newStatus, String reason) {
    try {
      lifecycleManager.transition(order.getOrderId(), newStatus, null, reason);
    } catch (IllegalStateTransitionException e) {
      LOG.debug("Skipping iceberg transition for order {}: {}", order.getOrderId(), e.getMessage());
    }
  }
}
