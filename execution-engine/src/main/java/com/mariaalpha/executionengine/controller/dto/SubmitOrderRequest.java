package com.mariaalpha.executionengine.controller.dto;

import com.mariaalpha.executionengine.controller.validation.ValidSubmitOrderRequest;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import com.mariaalpha.executionengine.pegged.PegType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@ValidSubmitOrderRequest
public record SubmitOrderRequest(
    @NotBlank String symbol,
    @NotNull Side side,
    @NotNull OrderType orderType,
    @Min(1) int quantity,
    @DecimalMin("0.01") BigDecimal limitPrice,
    @DecimalMin("0.01") BigDecimal stopPrice,
    @Min(1) Integer displayQuantity,
    TimeInForce tif,
    String clientOrderId,
    PegType pegType,
    Integer pegOffsetBps) {

  /**
   * Legacy constructor: defaults peg fields to null. Kept for backward-compatibility with the
   * non-PEGGED order types and the test suite that predates roadmap 3.2.3.
   */
  public SubmitOrderRequest(
      String symbol,
      Side side,
      OrderType orderType,
      int quantity,
      BigDecimal limitPrice,
      BigDecimal stopPrice,
      Integer displayQuantity,
      TimeInForce tif,
      String clientOrderId) {
    this(
        symbol,
        side,
        orderType,
        quantity,
        limitPrice,
        stopPrice,
        displayQuantity,
        tif,
        clientOrderId,
        null,
        null);
  }
}
