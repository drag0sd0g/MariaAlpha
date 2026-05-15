package com.mariaalpha.executionengine.router;

import com.mariaalpha.executionengine.config.SorConfig;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class VenueRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(VenueRegistry.class);
  private static final double WEIGHT_TOLERANCE = 1e-6;

  private final SorConfig config;
  private final List<Venue> venues;

  public VenueRegistry(SorConfig config) {
    this.config = config;
    this.venues = config.venues() == null ? List.of() : List.copyOf(config.venues());
  }

  @PostConstruct
  public void validate() {
    var weights = config.weights();
    if (weights == null) {
      throw new IllegalStateException("execution-engine.sor.weights is required");
    }
    if (Math.abs(weights.sum() - 1.0) > WEIGHT_TOLERANCE) {
      throw new IllegalStateException(
          "execution-engine.sor.weights must sum to 1.0 (was " + weights.sum() + ")");
    }
    if (venues.isEmpty()) {
      LOG.warn("VenueRegistry initialised with zero venues — SOR will degrade to no-op");
    } else {
      LOG.info(
          "VenueRegistry initialised with {} venues ({} enabled): {}",
          venues.size(),
          enabled().size(),
          venues.stream().map(Venue::name).toList());
    }
  }

  public List<Venue> all() {
    return venues;
  }

  public List<Venue> enabled() {
    return venues.stream().filter(Venue::enabled).toList();
  }

  public Optional<Venue> byName(String name) {
    return venues.stream().filter(venue -> venue.name().equals(name)).findFirst();
  }
}
