package com.mariaalpha.executionengine.adapter;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class VenueAdapterRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(VenueAdapterRegistry.class);

  private final List<VenueAdapter> adapters;
  private final Map<String, VenueAdapter> byName;

  public VenueAdapterRegistry(List<VenueAdapter> adapters) {
    this.adapters = List.copyOf(adapters);
    var collected = new LinkedHashMap<String, VenueAdapter>();
    for (var adapter : adapters) {
      var existing = collected.putIfAbsent(adapter.venueName(), adapter);
      if (existing != null) {
        throw new IllegalStateException(
            "Duplicate venue name '" + adapter.venueName() + "' across VenueAdapter beans");
      }
    }
    this.byName = collected;
  }

  @PostConstruct
  void log() {
    LOG.info("VenueAdapterRegistry registered {} adapters: {}", adapters.size(), byName.keySet());
  }

  public Optional<VenueAdapter> get(String venueName) {
    return Optional.ofNullable(byName.get(venueName));
  }

  public Set<String> healthyNames() {
    return adapters.stream()
        .filter(VenueAdapter::isHealthy)
        .map(VenueAdapter::venueName)
        .collect(Collectors.toSet());
  }

  public List<VenueAdapter> adapters() {
    return adapters;
  }

  public Map<String, VenueAdapter> snapshot() {
    return new HashMap<>(byName);
  }
}
