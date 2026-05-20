package com.mariaalpha.executionengine.controller.validation;

import com.mariaalpha.executionengine.controller.dto.SubmitOrderRequest;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.TimeInForce;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.EnumSet;
import java.util.Set;

public class SubmitOrderRequestConstraints
    implements ConstraintValidator<ValidSubmitOrderRequest, SubmitOrderRequest> {

  private static final Set<OrderType> ICEBERG_ONLY = EnumSet.of(OrderType.ICEBERG);
  private static final Set<OrderType> FORBIDS_CUSTOM_TIF =
      EnumSet.of(OrderType.MARKET, OrderType.STOP);

  @Override
  public boolean isValid(SubmitOrderRequest request, ConstraintValidatorContext ctx) {
    if (request == null) {
      return true; // @NotNull on field handles null requests
    }
    ctx.disableDefaultConstraintViolation();

    // displayQuantity is required iff ICEBERG, forbidden otherwise.
    boolean isIceberg = ICEBERG_ONLY.contains(request.orderType());
    if (isIceberg && request.displayQuantity() == null) {
      return violation(ctx, "displayQuantity is required for ICEBERG orders");
    }
    if (!isIceberg && request.displayQuantity() != null) {
      return violation(ctx, "displayQuantity is only valid for ICEBERG orders");
    }
    if (isIceberg && request.displayQuantity() >= request.quantity()) {
      return violation(ctx, "displayQuantity must be strictly less than quantity");
    }

    // TIF rules.
    var tif = request.tif();
    if (FORBIDS_CUSTOM_TIF.contains(request.orderType()) && tif != null && tif != TimeInForce.DAY) {
      return violation(ctx, "MARKET and STOP orders only accept tif=DAY (or null)");
    }
    if (request.orderType() == OrderType.IOC && tif != null && tif != TimeInForce.IOC) {
      return violation(ctx, "IOC orders only accept tif=IOC (or null)");
    }
    if (request.orderType() == OrderType.FOK && tif != null && tif != TimeInForce.FOK) {
      return violation(ctx, "FOK orders only accept tif=FOK (or null)");
    }
    if (request.orderType() == OrderType.GTC && tif != null && tif != TimeInForce.GTC) {
      return violation(ctx, "GTC orders only accept tif=GTC (or null)");
    }

    // Limit-bearing types must have limitPrice.
    if (requiresLimitPrice(request.orderType()) && request.limitPrice() == null) {
      return violation(ctx, request.orderType() + " orders require limitPrice");
    }
    if (request.orderType() == OrderType.STOP && request.stopPrice() == null) {
      return violation(ctx, "STOP orders require stopPrice");
    }
    return true;
  }

  private static boolean requiresLimitPrice(OrderType t) {
    return t == OrderType.LIMIT
        || t == OrderType.IOC
        || t == OrderType.FOK
        || t == OrderType.GTC
        || t == OrderType.ICEBERG;
  }

  private static boolean violation(ConstraintValidatorContext ctx, String msg) {
    ctx.buildConstraintViolationWithTemplate(msg).addConstraintViolation();
    return false;
  }
}
