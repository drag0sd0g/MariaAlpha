package com.mariaalpha.executionengine.handler;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.OrderType;
import org.springframework.stereotype.Component;

@Component
public class AlpacaOrderTypeMapper {

  public String wireType(OrderType orderType) {
    return switch (orderType) {
      case MARKET -> "market";
      case LIMIT -> "limit";
      case STOP -> "stop";
      case IOC, FOK, GTC -> "limit";
      case ICEBERG ->
          throw new IllegalArgumentException(
              "ICEBERG orders must not be submitted directly to Alpaca — they are sliced by"
                  + " IcebergCoordinator into LIMIT children");
      case PEGGED ->
          throw new IllegalArgumentException(
              "PEGGED orders must not be submitted directly to Alpaca — they are fronted by"
                  + " PeggedCoordinator with re-pegging LIMIT children");
    };
  }

  public String wireTif(ExecutionInstruction instruction) {
    return instruction.timeInForce().wireValue();
  }
}
