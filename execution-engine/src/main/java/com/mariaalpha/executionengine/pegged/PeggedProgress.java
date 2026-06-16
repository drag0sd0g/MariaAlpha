package com.mariaalpha.executionengine.pegged;

import java.math.BigDecimal;

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
