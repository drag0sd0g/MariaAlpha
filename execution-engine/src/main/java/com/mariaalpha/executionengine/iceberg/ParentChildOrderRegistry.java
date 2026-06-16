package com.mariaalpha.executionengine.iceberg;

import com.mariaalpha.executionengine.model.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ParentChildOrderRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ParentChildOrderRegistry.class);

  private final Map<String, Order> parents = new ConcurrentHashMap<>();
  private final Map<String, String> childToParent = new ConcurrentHashMap<>();
  private final Map<String, List<String>> parentToChildren = new ConcurrentHashMap<>();
  private final Map<String, IcebergProgress> progress = new ConcurrentHashMap<>();

  public void recordParent(Order parent, int displayQuantity) {
    parents.put(parent.getOrderId(), parent);
    progress.put(
        parent.getOrderId(),
        new IcebergProgress(parent.getQuantity(), displayQuantity, 0, 0, 0, null));
    parentToChildren.put(parent.getOrderId(), new ArrayList<>());
    LOG.debug(
        "Iceberg parent registered: {} qty={} display={}",
        parent.getOrderId(),
        parent.getQuantity(),
        displayQuantity);
  }

  public void linkChildToParent(Order child, Order parent, int sliceQty) {
    childToParent.put(child.getOrderId(), parent.getOrderId());
    parentToChildren.compute(
        parent.getOrderId(),
        (k, v) -> {
          var list = v != null ? v : new ArrayList<String>();
          list.add(child.getOrderId());
          return list;
        });
    progress.compute(
        parent.getOrderId(),
        (k, v) -> v == null ? null : v.withChildSubmitted(sliceQty, child.getOrderId()));
  }

  public Optional<Order> parentFor(String childOrderId) {
    var parentId = childToParent.get(childOrderId);
    return parentId == null ? Optional.empty() : Optional.ofNullable(parents.get(parentId));
  }

  public Optional<IcebergProgress> progress(String parentOrderId) {
    return Optional.ofNullable(progress.get(parentOrderId));
  }

  public IcebergProgress recordChildFill(String parentOrderId, int fillQty, boolean childComplete) {
    return progress.compute(
        parentOrderId, (k, v) -> v == null ? null : v.withChildFill(fillQty, childComplete));
  }

  public Optional<String> activeChildFor(String parentOrderId) {
    return progress(parentOrderId).map(IcebergProgress::activeChildOrderId).filter(s -> s != null);
  }

  public void removeParent(String parentOrderId) {
    parents.remove(parentOrderId);
    var children = parentToChildren.remove(parentOrderId);
    if (children != null) {
      children.forEach(childToParent::remove);
    }
    progress.remove(parentOrderId);
  }

  public int trackedParents() {
    return parents.size();
  }
}
