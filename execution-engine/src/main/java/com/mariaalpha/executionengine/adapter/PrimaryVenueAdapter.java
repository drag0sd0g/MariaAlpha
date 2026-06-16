package com.mariaalpha.executionengine.adapter;

import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.OrderAck;
import com.mariaalpha.executionengine.router.Venue;
import com.mariaalpha.executionengine.router.VenueType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class PrimaryVenueAdapter implements VenueAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(PrimaryVenueAdapter.class);

  private final ExchangeAdapter delegate;
  private final String venueName;

  public PrimaryVenueAdapter(ExchangeAdapter delegate, SorConfig sorConfig) {
    this.delegate = delegate;
    this.venueName =
        sorConfig.venues().stream()
            .filter(v -> "primary".equalsIgnoreCase(v.adapterBean()))
            .filter(Venue::enabled)
            .findFirst()
            .map(Venue::name)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No enabled venue with adapter-bean=primary in"
                            + " execution-engine.sor.venues;"
                            + " PrimaryVenueAdapter cannot resolve its venue name"));
  }

  @PostConstruct
  void log() {
    LOG.info(
        "PrimaryVenueAdapter active as venue '{}' (delegate {})",
        venueName,
        delegate.getClass().getSimpleName());
  }

  @Override
  public String venueName() {
    return venueName;
  }

  @Override
  public VenueType venueType() {
    return VenueType.LIT;
  }

  @Override
  public OrderAck submitOrder(ExecutionInstruction instruction) {
    return delegate.submitOrder(instruction);
  }

  @Override
  public OrderAck cancelOrder(String exchangeOrderId) {
    return delegate.cancelOrder(exchangeOrderId);
  }

  @Override
  public void onExecutionReport(Consumer<ExecutionReport> callback) {
    delegate.onExecutionReport(callback);
  }

  @Override
  public void start() {}

  @PreDestroy
  @Override
  public void shutdown() {}

  @Override
  public boolean isHealthy() {
    return delegate.isHealthy();
  }
}
