package com.mariaalpha.executionengine.pegged;

import java.math.BigDecimal;

/**
 * Immutable progress snapshot for a single PEGGED parent order — fill state, last reference price,
 * last submitted price, and the orderId of the currently-active LIMIT child (if any). All updates
 * return a new instance so the registry can use the {@link java.util.concurrent.ConcurrentHashMap}
 * compute idiom without lock-management bugs.
 */
public record PeggedProgress(
    int totalQuantity,
    int filledQuantity,
    int repegsTotal,
    BigDecimal lastReferencePrice,
    BigDecimal lastSubmittedPrice,
    String activeChildOrderId) {

  public PeggedProgress withChildSubmitted(
      String childOrderId, BigDecimal referencePrice, BigDecimal submittedPrice, boolean isRepeg) {
    return new PeggedProgress(
        totalQuantity,
        filledQuantity,
        repegsTotal + (isRepeg ? 1 : 0),
        referencePrice,
        submittedPrice,
        childOrderId);
  }

  public PeggedProgress withChildFill(int fillQty, boolean childComplete) {
    return new PeggedProgress(
        totalQuantity,
        filledQuantity + fillQty,
        repegsTotal,
        lastReferencePrice,
        lastSubmittedPrice,
        childComplete ? null : activeChildOrderId);
  }

  public PeggedProgress withChildCancelled() {
    return new PeggedProgress(
        totalQuantity, filledQuantity, repegsTotal, lastReferencePrice, lastSubmittedPrice, null);
  }

  public int remainingQuantity() {
    return totalQuantity - filledQuantity;
  }

  public boolean parentComplete() {
    return filledQuantity >= totalQuantity;
  }
}
