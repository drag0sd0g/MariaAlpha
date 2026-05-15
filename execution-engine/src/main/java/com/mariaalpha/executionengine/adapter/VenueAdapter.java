package com.mariaalpha.executionengine.adapter;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.OrderAck;
import com.mariaalpha.executionengine.router.VenueType;
import java.util.function.Consumer;

public interface VenueAdapter {

  String venueName();

  VenueType venueType();

  OrderAck submitOrder(ExecutionInstruction instruction);

  OrderAck cancelOrder(String exchangeOrderId);

  void onExecutionReport(Consumer<ExecutionReport> callback);

  void start();

  void shutdown();

  boolean isHealthy();
}
