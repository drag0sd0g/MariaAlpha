package com.mariaalpha.executionengine.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mariaalpha.executionengine.config.SorConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class VenueRegistryTest {

  private static final SorConfig.Weights GOOD_WEIGHTS =
      new SorConfig.Weights(0.25, 0.25, 0.10, 0.15, 0.25);

  @Test
  void enabledFiltersOutDisabled() {
    var lit = venue("LIT", true);
    var dark = venue("DARK", false);
    var registry = registry(GOOD_WEIGHTS, List.of(lit, dark));
    registry.validate();
    assertThat(registry.all()).containsExactly(lit, dark);
    assertThat(registry.enabled()).containsExactly(lit);
  }

  @Test
  void byNameReturnsFirstMatch() {
    var lit = venue("LIT", true);
    var registry = registry(GOOD_WEIGHTS, List.of(lit));
    registry.validate();
    assertThat(registry.byName("LIT")).contains(lit);
    assertThat(registry.byName("MISSING")).isEmpty();
  }

  @Test
  void emptyRegistryIsAllowed() {
    var registry = registry(GOOD_WEIGHTS, List.of());
    registry.validate();
    assertThat(registry.all()).isEmpty();
    assertThat(registry.enabled()).isEmpty();
  }

  @Test
  void misconfiguredWeightsReject() {
    var bad = new SorConfig.Weights(0.1, 0.1, 0.1, 0.1, 0.1);
    var registry = registry(bad, List.of(venue("LIT", true)));
    assertThatThrownBy(registry::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must sum to 1.0");
  }

  private static Venue venue(String name, boolean enabled) {
    return new Venue(name, VenueType.LIT, 50, 3, 2, 1.0, 10000, 0.95, "primary", enabled);
  }

  private static VenueRegistry registry(SorConfig.Weights weights, List<Venue> venues) {
    var config = new SorConfig("scored", 200, 10, 5, 1000, weights, venues);
    return new VenueRegistry(config);
  }
}
