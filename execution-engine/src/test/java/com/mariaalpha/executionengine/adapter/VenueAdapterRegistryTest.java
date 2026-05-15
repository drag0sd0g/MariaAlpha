package com.mariaalpha.executionengine.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.router.VenueType;
import java.util.List;
import org.junit.jupiter.api.Test;

class VenueAdapterRegistryTest {

  @Test
  void byNameResolves() {
    var primary = stub("PRIMARY", VenueType.LIT, true);
    var dark = stub("DARK_POOL_A", VenueType.DARK, true);
    var registry = new VenueAdapterRegistry(List.of(primary, dark));
    assertThat(registry.get("PRIMARY")).contains(primary);
    assertThat(registry.get("DARK_POOL_A")).contains(dark);
    assertThat(registry.get("MISSING")).isEmpty();
  }

  @Test
  void healthyFiltersUnhealthy() {
    var primary = stub("PRIMARY", VenueType.LIT, true);
    var dark = stub("DARK_POOL_A", VenueType.DARK, false);
    var registry = new VenueAdapterRegistry(List.of(primary, dark));
    assertThat(registry.healthyNames()).containsExactly("PRIMARY");
  }

  @Test
  void duplicateVenueNameThrows() {
    var a = stub("PRIMARY", VenueType.LIT, true);
    var b = stub("PRIMARY", VenueType.LIT, true);
    assertThatThrownBy(() -> new VenueAdapterRegistry(List.of(a, b)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate");
  }

  @Test
  void adaptersReturnsListSnapshot() {
    var primary = stub("PRIMARY", VenueType.LIT, true);
    var registry = new VenueAdapterRegistry(List.of(primary));
    assertThat(registry.adapters()).containsExactly(primary);
  }

  private static VenueAdapter stub(String name, VenueType type, boolean healthy) {
    var a = mock(VenueAdapter.class);
    when(a.venueName()).thenReturn(name);
    when(a.venueType()).thenReturn(type);
    when(a.isHealthy()).thenReturn(healthy);
    return a;
  }
}
