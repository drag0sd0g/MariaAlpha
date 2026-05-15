package com.mariaalpha.executionengine.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.router.Venue;
import com.mariaalpha.executionengine.router.VenueType;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PrimaryVenueAdapterTest {

  @Test
  void resolvesVenueNameFromSorConfig() {
    var v = new Venue("SIMULATED", VenueType.LIT, 50, 0, 0, 1.0, 10000, 1.0, "primary", true);
    var sor = sorConfig(List.of(v));
    var delegate = mock(ExchangeAdapter.class);
    var primary = new PrimaryVenueAdapter(delegate, sor);
    assertThat(primary.venueName()).isEqualTo("SIMULATED");
    assertThat(primary.venueType()).isEqualTo(VenueType.LIT);
  }

  @Test
  void throwsWhenNoPrimaryConfigured() {
    var v = new Venue("DARK_POOL_A", VenueType.DARK, 30, 1, 0, 0.2, 0, 0.4, "dark-pool-a", true);
    var sor = sorConfig(List.of(v));
    var delegate = mock(ExchangeAdapter.class);
    assertThatThrownBy(() -> new PrimaryVenueAdapter(delegate, sor))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void submitDelegates() {
    var instruction = mock(ExecutionInstruction.class);
    var delegate = mock(ExchangeAdapter.class);
    var primary = new PrimaryVenueAdapter(delegate, sorConfig(List.of(primaryVenue())));
    primary.submitOrder(instruction);
    verify(delegate).submitOrder(instruction);
  }

  @Test
  void cancelDelegates() {
    var delegate = mock(ExchangeAdapter.class);
    var primary = new PrimaryVenueAdapter(delegate, sorConfig(List.of(primaryVenue())));
    primary.cancelOrder("ex-1");
    verify(delegate).cancelOrder("ex-1");
  }

  @Test
  void healthDelegates() {
    var delegate = mock(ExchangeAdapter.class);
    when(delegate.isHealthy()).thenReturn(true);
    var primary = new PrimaryVenueAdapter(delegate, sorConfig(List.of(primaryVenue())));
    assertThat(primary.isHealthy()).isTrue();
  }

  @Test
  void executionReportCallbackDelegates() {
    var delegate = mock(ExchangeAdapter.class);
    var primary = new PrimaryVenueAdapter(delegate, sorConfig(List.of(primaryVenue())));
    Consumer<ExecutionReport> cb = r -> {};
    primary.onExecutionReport(cb);
    verify(delegate).onExecutionReport(any());
  }

  @Test
  void startAndShutdownAreNoOps() {
    var delegate = mock(ExchangeAdapter.class);
    var primary = new PrimaryVenueAdapter(delegate, sorConfig(List.of(primaryVenue())));
    primary.start();
    primary.shutdown();
    // PrimaryVenueAdapter never invokes the wrapped adapter's lifecycle —
    // ExchangeAdapter manages its own @PostConstruct/@PreDestroy.
    verifyNoInteractions(delegate);
  }

  @Test
  void skipsDisabledPrimaryEntries() {
    // First entry is primary but disabled — adapter should fall back to next enabled match.
    var disabled =
        new Venue("OLD_PRIMARY", VenueType.LIT, 50, 0, 0, 1.0, 10000, 1.0, "primary", false);
    var enabled =
        new Venue("NEW_PRIMARY", VenueType.LIT, 50, 0, 0, 1.0, 10000, 1.0, "primary", true);
    var delegate = mock(ExchangeAdapter.class);
    var primary = new PrimaryVenueAdapter(delegate, sorConfig(List.of(disabled, enabled)));
    assertThat(primary.venueName()).isEqualTo("NEW_PRIMARY");
  }

  private static Venue primaryVenue() {
    return new Venue("PRIMARY", VenueType.LIT, 50, 3, 2, 1.0, 10000, 0.95, "primary", true);
  }

  private static SorConfig sorConfig(List<Venue> venues) {
    var weights = new SorConfig.Weights(0.25, 0.25, 0.10, 0.15, 0.25);
    return new SorConfig("scored", 200, 10, 5, 1000, weights, venues);
  }
}
