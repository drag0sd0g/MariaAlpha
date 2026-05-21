package com.mariaalpha.executionengine.iceberg;

public record IcebergProgressView(
    String parentOrderId,
    int totalQuantity,
    int displayQuantity,
    int submittedQuantity,
    int filledQuantity,
    int slicesSubmitted,
    String activeChildOrderId) {

  public static IcebergProgressView of(String parentOrderId, IcebergProgress p) {
    return new IcebergProgressView(
        parentOrderId,
        p.totalQuantity(),
        p.displayQuantity(),
        p.submittedQuantity(),
        p.filledQuantity(),
        p.slicesSubmitted(),
        p.activeChildOrderId());
  }
}
