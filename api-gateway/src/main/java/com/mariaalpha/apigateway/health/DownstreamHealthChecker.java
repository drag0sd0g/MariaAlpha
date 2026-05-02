package com.mariaalpha.apigateway.health;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mariaalpha.apigateway.config.DownstreamServicesProperties.Downstream;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Why Caffeine: every K8s readiness probe at 10 Hz × 5 downstreams = 50 outbound requests/sec just
 * from probes. Caffeine's 5-second TTL caps this at ~1 req/sec/downstream. The async API integrates
 * cleanly with Reactor.
 */
@Component
public class DownstreamHealthChecker {

  private static final Logger LOG = LoggerFactory.getLogger(DownstreamHealthChecker.class);
  private static final Duration CACHE_TTL = Duration.ofSeconds(5);
  private static final Duration CHECK_TIMEOUT = Duration.ofMillis(500);

  private final WebClient.Builder webClientBuilder;
  private final AsyncCache<String, DownstreamStatus> cache;

  public DownstreamHealthChecker(WebClient.Builder healthcheckWebClientBuilder) {
    this.webClientBuilder = healthcheckWebClientBuilder;
    this.cache = Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).maximumSize(32).buildAsync();
  }

  public Mono<DownstreamStatus> check(String name, Downstream downstream) {
    return Mono.fromFuture(cache.get(name, (k, executor) -> doCheck(k, downstream).toFuture()));
  }

  private Mono<DownstreamStatus> doCheck(String name, Downstream downstream) {
    return webClientBuilder
        .build()
        .get()
        .uri(downstream.healthUrl())
        .retrieve()
        .toBodilessEntity()
        .timeout(CHECK_TIMEOUT)
        .map(response -> DownstreamStatus.up(name))
        .onErrorResume(
            ex -> {
              LOG.debug("downstream {} health check failed: {}", name, ex.toString());
              return Mono.just(DownstreamStatus.down(name, ex.getClass().getSimpleName()));
            });
  }

  public void invalidate() {
    cache.synchronous().invalidateAll();
  }
}
