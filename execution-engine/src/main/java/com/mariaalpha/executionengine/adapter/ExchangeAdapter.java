package com.mariaalpha.executionengine.adapter;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.OrderAck;
import java.util.function.Consumer;

public interface ExchangeAdapter {

  /** Submit an order. Returns acknowledgement with exchange order ID. */
  OrderAck submitOrder(ExecutionInstruction instruction);

  /** Request cancellation of an order. */
  OrderAck cancelOrder(String exchangeOrderId);

  /**
   * Register a callback for execution reports (fills). The adapter calls this whenever a fill or
   * status update arrives.
   */
  void onExecutionReport(Consumer<ExecutionReport> callback);

  /** Start the adapter (connect WebSocket, etc.). */
  void start();

  /** Shut down the adapter gracefully. */
  void shutdown();

  /** Returns true if the adapter is connected and operational. */
  boolean isHealthy();
}
