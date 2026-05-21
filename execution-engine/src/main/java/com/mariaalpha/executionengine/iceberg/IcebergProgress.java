package com.mariaalpha.executionengine.iceberg;

public record IcebergProgress(
    int totalQuantity,
    int displayQuantity,
    int submittedQuantity,
    int filledQuantity,
    int slicesSubmitted,
    String activeChildOrderId) {

  public IcebergProgress withChildSubmitted(int sliceQty, String childOrderId) {
    return new IcebergProgress(
        totalQuantity,
        displayQuantity,
        submittedQuantity + sliceQty,
        filledQuantity,
        slicesSubmitted + 1,
        childOrderId);
  }

  public IcebergProgress withChildFill(int fillQty, boolean childComplete) {
    return new IcebergProgress(
        totalQuantity,
        displayQuantity,
        submittedQuantity,
        filledQuantity + fillQty,
        slicesSubmitted,
        childComplete ? null : activeChildOrderId);
  }

  public int remainingToSubmit() {
    return totalQuantity - submittedQuantity;
  }

  public boolean parentComplete() {
    return filledQuantity >= totalQuantity;
  }
}
