package com.mariaalpha.executionengine.handler;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.OrderType;
import org.springframework.stereotype.Component;

/**
 * Translates the internal {@link OrderType} + {@link
 * com.mariaalpha.executionengine.model.TimeInForce} pair into the Alpaca-wire ({@code type}, {@code
 * time_in_force}) tuple.
 *
 * <p>Centralised here so the adapter stays a thin HTTP shim and so wire mappings can be unit-tested
 * without spinning the adapter up.
 */
@Component
public class AlpacaOrderTypeMapper {

  public String wireType(OrderType orderType) {
    return switch (orderType) {
      case MARKET -> "market";
      case LIMIT -> "limit";
      case STOP -> "stop";
      // IOC / FOK / GTC are TIF overlays on a limit order in Alpaca's model
      case IOC, FOK, GTC -> "limit";
      case ICEBERG ->
          throw new IllegalArgumentException(
              "ICEBERG orders must not be submitted directly to Alpaca — they are sliced by"
                  + " IcebergCoordinator into LIMIT children");
    };
  }

  public String wireTif(ExecutionInstruction instruction) {
    return instruction.timeInForce().wireValue();
  }
}
