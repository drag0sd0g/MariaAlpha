package com.mariaalpha.apigateway.health;

import com.mariaalpha.apigateway.config.DownstreamServicesProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component("downstreams")
public class AggregateDownstreamHealthIndicator implements ReactiveHealthIndicator {

  private final DownstreamServicesProperties properties;
  private final DownstreamHealthChecker checker;

  public AggregateDownstreamHealthIndicator(
      DownstreamServicesProperties properties, DownstreamHealthChecker checker) {
    this.properties = properties;
    this.checker = checker;
  }

  @Override
  public Mono<Health> health() {
    Map<String, DownstreamServicesProperties.Downstream> downstreams = properties.downstreams();
    if (downstreams == null || downstreams.isEmpty()) {
      return Mono.just(Health.up().withDetail("downstreams", "none-configured").build());
    }

    return Flux.fromIterable(downstreams.entrySet())
        .flatMap(entry -> checker.check(entry.getKey(), entry.getValue()))
        .collectList()
        .map(statuses -> aggregate(statuses, downstreams));
  }

  private Health aggregate(
      List<DownstreamStatus> statuses,
      Map<String, DownstreamServicesProperties.Downstream> downstreams) {
    Map<String, Object> details = new LinkedHashMap<>();
    boolean anyRequiredDown = false;
    for (DownstreamStatus s : statuses) {
      DownstreamServicesProperties.Downstream cfg = downstreams.get(s.name());
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("status", s.up() ? "UP" : "DOWN");
      entry.put("required", cfg != null && cfg.required());
      entry.put("detail", s.detail());
      details.put(s.name(), entry);
      if (!s.up() && cfg != null && cfg.required()) {
        anyRequiredDown = true;
      }
    }
    Health.Builder builder = anyRequiredDown ? Health.outOfService() : Health.up();
    return builder.withDetails(details).build();
  }
}
