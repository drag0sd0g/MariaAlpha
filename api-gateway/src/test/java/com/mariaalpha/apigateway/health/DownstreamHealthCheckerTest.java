package com.mariaalpha.apigateway.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.apigateway.config.DownstreamServicesProperties.Downstream;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

@Tag("integration")
class DownstreamHealthCheckerTest {

  private MockWebServer server;
  private DownstreamHealthChecker checker;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
            .responseTimeout(Duration.ofMillis(500));
    WebClient.Builder builder =
        WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    checker = new DownstreamHealthChecker(builder);
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void reportsUpOn200() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"UP\"}"));
    var d = downstream(server.url("/").toString());

    StepVerifier.create(checker.check("test", d))
        .assertNext(s -> assertThat(s.up()).isTrue())
        .verifyComplete();
  }

  @Test
  void reportsDownOn500() {
    server.enqueue(new MockResponse().setResponseCode(500));
    var d = downstream(server.url("/").toString());

    StepVerifier.create(checker.check("test", d))
        .assertNext(s -> assertThat(s.up()).isFalse())
        .verifyComplete();
  }

  @Test
  void reportsDownOnTimeout() {
    // setHeadersDelay (not setBodyDelay) is required: toBodilessEntity completes once headers
    // arrive, so a body-only delay is not observable.
    server.enqueue(new MockResponse().setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS));
    var d = downstream(server.url("/").toString());

    StepVerifier.create(checker.check("test", d))
        .assertNext(s -> assertThat(s.up()).isFalse())
        .verifyComplete();
  }

  @Test
  void cachesResultWithinTtl() {
    server.enqueue(new MockResponse().setResponseCode(200));
    var d = downstream(server.url("/").toString());

    StepVerifier.create(checker.check("test", d)).expectNextCount(1).verifyComplete();
    StepVerifier.create(checker.check("test", d)).expectNextCount(1).verifyComplete();

    // Only one HTTP call should have hit the server.
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  private static Downstream downstream(String base) {
    return new Downstream(base, base, true);
  }
}
