package com.mariaalpha.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ApiKeyAuthenticationFilterLoggingTest {

  private static final String SECRET = "tHis_IS_a_SeCREt_42";
  private static final SecurityProperties PROPS =
      new SecurityProperties(SECRET, "X-API-Key", "apiKey", List.of("/actuator/**"));

  private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
  private Logger root;

  @BeforeEach
  void attach() {
    root = (Logger) LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    listAppender.start();
    root.addAppender(listAppender);
    root.setLevel(Level.DEBUG);
  }

  @AfterEach
  void detach() {
    root.detachAppender(listAppender);
  }

  @Test
  void keyNeverAppearsInLogsOnSuccess() {
    var filter = new ApiKeyAuthenticationFilter(PROPS);
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders").header("X-API-Key", SECRET));
    var chain =
        new WebFilterChain() {
          @Override
          public Mono<Void> filter(ServerWebExchange e) {
            return Mono.empty();
          }
        };

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(msg -> msg.contains(SECRET));
  }

  @Test
  void keyNeverAppearsInLogsOnFailure() {
    var filter = new ApiKeyAuthenticationFilter(PROPS);
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders").header("X-API-Key", "wrong-key-XYZ"));
    var chain =
        new WebFilterChain() {
          @Override
          public Mono<Void> filter(ServerWebExchange e) {
            return Mono.empty();
          }
        };

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(msg -> msg.contains("wrong-key-XYZ"));
  }
}
