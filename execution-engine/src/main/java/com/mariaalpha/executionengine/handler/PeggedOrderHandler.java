package com.mariaalpha.executionengine.handler;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import com.mariaalpha.executionengine.model.ValidationResult;
import com.mariaalpha.executionengine.pegged.PeggedConfig;
import com.mariaalpha.executionengine.pegged.PeggedPriceCalculator;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Validates PEGGED parent orders (roadmap 3.2.3). The execution-engine never submits a PEGGED
 * parent directly to a venue — the parent is captured by {@link
 * com.mariaalpha.executionengine.pegged.PeggedCoordinator}, which produces LIMIT child orders at
 * the pegged price and re-pegs them as the NBBO moves. {@link #toExecutionInstruction} is therefore
 * a preview path only (used by the manual order REST endpoint and tests).
 */
@Component
public class PeggedOrderHandler implements OrderTypeHandler {

  private final PeggedPriceCalculator priceCalculator;
  private final PeggedConfig config;

  public PeggedOrderHandler(PeggedPriceCalculator priceCalculator, PeggedConfig config) {
    this.priceCalculator = priceCalculator;
    this.config = config;
  }

  @Override
  public OrderType supportedType() {
    return OrderType.PEGGED;
  }

  @Override
  public ValidationResult validate(Order order, MarketState marketState) {
    if (order.getQuantity() <= 0) {
      return ValidationResult.fail("Quantity must be positive");
    }
    if (order.getPegType() == null) {
      return ValidationResult.fail("PEGGED order requires pegType");
    }
    int offset = order.getPegOffsetBps() == null ? 0 : order.getPegOffsetBps();
    if (Math.abs(offset) > config.maxOffsetBps()) {
      return ValidationResult.fail(
          "|pegOffsetBps| ("
              + Math.abs(offset)
              + ") exceeds configured maximum ("
              + config.maxOffsetBps()
              + ")");
    }
    if (marketState == null) {
      return ValidationResult.fail("No market data available for " + order.getSymbol());
    }
    BigDecimal reference =
        priceCalculator.referencePrice(order.getPegType(), order.getSide(), marketState);
    if (reference == null || reference.compareTo(BigDecimal.ZERO) <= 0) {
      return ValidationResult.fail(
          "PEGGED reference price unavailable for "
              + order.getSymbol()
              + " (peg="
              + order.getPegType()
              + ", side="
              + order.getSide()
              + ")");
    }
    if (order.getLimitPrice() != null) {
      // Cap must be on the right side of zero for the configured side.
      if (order.getLimitPrice().compareTo(BigDecimal.ZERO) <= 0) {
        return ValidationResult.fail("priceCap must be positive when supplied");
      }
      // For BUY, a cap below the current reference makes sense only if the user wants the order
      // to start clamped (acceptable); but a cap < 0.5× reference is almost certainly a typo and
      // would never fill — reject it.
      if (order.getSide() == Side.BUY
          && order.getLimitPrice().compareTo(reference.multiply(BigDecimal.valueOf(0.5))) < 0) {
        return ValidationResult.fail(
            "BUY priceCap is < 50% of the reference price — almost certainly a misconfiguration");
      }
      if (order.getSide() == Side.SELL
          && order.getLimitPrice().compareTo(reference.multiply(BigDecimal.valueOf(2.0))) > 0) {
        return ValidationResult.fail(
            "SELL priceCap is > 2× the reference price — almost certainly a misconfiguration");
      }
    }
    return ValidationResult.ok();
  }

  @Override
  public ExecutionInstruction toExecutionInstruction(Order order) {
    // Preview path: caller has already validated, so marketState lookups are not re-run here. The
    // adjustedLimitPrice on the returned instruction is the current pegged price as seen by the
    // handler — the coordinator computes its own fresh price at submit time.
    return new ExecutionInstruction(order, TimeInForce.DAY, order.getLimitPrice());
  }
}
