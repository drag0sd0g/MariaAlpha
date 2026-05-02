package com.mariaalpha.apigateway.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.apigateway.config.DownstreamServicesProperties;
import com.mariaalpha.apigateway.config.DownstreamServicesProperties.Downstream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AggregateDownstreamHealthIndicatorTest {

  @Test
  void allUp() {
    var props = props(req("a"), req("b"));
    var checker = mock(DownstreamHealthChecker.class);
    when(checker.check(eq("a"), any())).thenReturn(Mono.just(DownstreamStatus.up("a")));
    when(checker.check(eq("b"), any())).thenReturn(Mono.just(DownstreamStatus.up("b")));

    var indicator = new AggregateDownstreamHealthIndicator(props, checker);

    StepVerifier.create(indicator.health())
        .assertNext(h -> assertThat(h.getStatus()).isEqualTo(Status.UP))
        .verifyComplete();
  }

  @Test
  void requiredDownReportsOutOfService() {
    var props = props(req("a"), req("b"));
    var checker = mock(DownstreamHealthChecker.class);
    when(checker.check(eq("a"), any())).thenReturn(Mono.just(DownstreamStatus.up("a")));
    when(checker.check(eq("b"), any()))
        .thenReturn(Mono.just(DownstreamStatus.down("b", "ConnectException")));

    var indicator = new AggregateDownstreamHealthIndicator(props, checker);

    StepVerifier.create(indicator.health())
        .assertNext(h -> assertThat(h.getStatus()).isEqualTo(Status.OUT_OF_SERVICE))
        .verifyComplete();
  }

  @Test
  void optionalDownStaysUp() {
    var props = props(req("a"), opt("analytics"));
    var checker = mock(DownstreamHealthChecker.class);
    when(checker.check(eq("a"), any())).thenReturn(Mono.just(DownstreamStatus.up("a")));
    when(checker.check(eq("analytics"), any()))
        .thenReturn(Mono.just(DownstreamStatus.down("analytics", "ConnectException")));

    var indicator = new AggregateDownstreamHealthIndicator(props, checker);

    StepVerifier.create(indicator.health())
        .assertNext(
            h -> {
              assertThat(h.getStatus()).isEqualTo(Status.UP);
              assertThat(h.getDetails()).containsKey("analytics");
            })
        .verifyComplete();
  }

  @Test
  void noDownstreamsConfigured() {
    var props = new DownstreamServicesProperties(Map.of());
    var checker = mock(DownstreamHealthChecker.class);

    var indicator = new AggregateDownstreamHealthIndicator(props, checker);

    StepVerifier.create(indicator.health())
        .assertNext(h -> assertThat(h.getStatus()).isEqualTo(Status.UP))
        .verifyComplete();
  }

  private static DownstreamServicesProperties props(Map.Entry<String, Downstream>... entries) {
    var map = new LinkedHashMap<String, Downstream>();
    for (var e : entries) {
      map.put(e.getKey(), e.getValue());
    }
    return new DownstreamServicesProperties(map);
  }

  private static Map.Entry<String, Downstream> req(String name) {
    return Map.entry(name, new Downstream("http://" + name + ":1", "http://" + name + ":2", true));
  }

  private static Map.Entry<String, Downstream> opt(String name) {
    return Map.entry(name, new Downstream("http://" + name + ":1", "http://" + name + ":2", false));
  }
}
