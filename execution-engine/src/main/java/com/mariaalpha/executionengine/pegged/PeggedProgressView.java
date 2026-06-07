package com.mariaalpha.executionengine.pegged;

import java.math.BigDecimal;

/**
 * Read-only projection of {@link PeggedProgress} for the REST endpoint. Includes the parent orderId
 * so the UI can render a row without holding onto the request.
 */
public record PeggedProgressView(
    String parentOrderId,
    int totalQuantity,
    int filledQuantity,
    int remainingQuantity,
    int repegsTotal,
    BigDecimal lastReferencePrice,
    BigDecimal lastSubmittedPrice,
    String activeChildOrderId,
    boolean parentComplete) {

  public static PeggedProgressView of(String parentOrderId, PeggedProgress progress) {
    return new PeggedProgressView(
        parentOrderId,
        progress.totalQuantity(),
        progress.filledQuantity(),
        progress.remainingQuantity(),
        progress.repegsTotal(),
        progress.lastReferencePrice(),
        progress.lastSubmittedPrice(),
        progress.activeChildOrderId(),
        progress.parentComplete());
  }
}
