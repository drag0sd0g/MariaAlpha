package com.mariaalpha.executionengine.pegged;

import com.mariaalpha.executionengine.model.Order;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Thread-safe registry linking PEGGED parent orders to their currently-active LIMIT child slice.
 *
 * <p>Mirrors {@link com.mariaalpha.executionengine.iceberg.ParentChildOrderRegistry} but with a
 * simpler model: a PEGGED parent has at most one open child at any time (the previous child is
 * cancelled before the new one is submitted), so {@code childToParent} is single-valued and the
 * {@link PeggedProgress} carries the activeChildOrderId directly.
 */
@Component
public class PeggedRegistry {

  private final Map<String, Order> parents = new ConcurrentHashMap<>();
  private final Map<String, String> childToParent = new ConcurrentHashMap<>();
  private final Map<String, PeggedProgress> progress = new ConcurrentHashMap<>();

  public void recordParent(Order parent) {
    parents.put(parent.getOrderId(), parent);
    progress.put(
        parent.getOrderId(), new PeggedProgress(parent.getQuantity(), 0, 0, null, null, null));
  }

  public void recordChildSubmitted(
      String parentOrderId,
      String childOrderId,
      BigDecimal referencePrice,
      BigDecimal submittedPrice,
      boolean isRepeg) {
    childToParent.put(childOrderId, parentOrderId);
    progress.compute(
        parentOrderId,
        (k, v) ->
            v == null
                ? null
                : v.withChildSubmitted(childOrderId, referencePrice, submittedPrice, isRepeg));
  }

  public Optional<Order> parentFor(String childOrderId) {
    var parentId = childToParent.get(childOrderId);
    return parentId == null ? Optional.empty() : Optional.ofNullable(parents.get(parentId));
  }

  public Optional<PeggedProgress> progress(String parentOrderId) {
    return Optional.ofNullable(progress.get(parentOrderId));
  }

  public PeggedProgress recordChildFill(String parentOrderId, int fillQty, boolean childComplete) {
    return progress.compute(
        parentOrderId, (k, v) -> v == null ? null : v.withChildFill(fillQty, childComplete));
  }

  public PeggedProgress recordChildCancelled(String parentOrderId, String childOrderId) {
    childToParent.remove(childOrderId);
    return progress.compute(parentOrderId, (k, v) -> v == null ? null : v.withChildCancelled());
  }

  public Optional<String> activeChildFor(String parentOrderId) {
    return progress(parentOrderId).map(PeggedProgress::activeChildOrderId).filter(s -> s != null);
  }

  public void removeParent(String parentOrderId) {
    parents.remove(parentOrderId);
    progress.remove(parentOrderId);
    childToParent.values().removeIf(parentOrderId::equals);
  }

  public java.util.Collection<String> trackedParentIds() {
    return parents.keySet();
  }

  public int trackedParents() {
    return parents.size();
  }
}
