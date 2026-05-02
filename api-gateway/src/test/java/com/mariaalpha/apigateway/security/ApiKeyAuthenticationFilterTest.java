package com.mariaalpha.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ApiKeyAuthenticationFilterTest {

  private static final SecurityProperties PROPS =
      new SecurityProperties(
          "secret-key", "X-API-Key", "apiKey", List.of("/actuator/**", "/swagger-ui/**"));

  private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(PROPS);

  @Test
  void allowsRequestWithValidHeader() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders").header("X-API-Key", "secret-key"));
    var chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    verify(chain).filter(exchange);
  }

  @Test
  void allowsRequestWithValidQueryParam() {
    var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/ws/orders?apiKey=secret-key"));
    var chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    verify(chain).filter(exchange);
  }

  @Test
  void rejectsMissingKey() {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders"));
    var chain = mock(WebFilterChain.class);

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verify(chain, never()).filter(any());
  }

  @Test
  void rejectsWrongKey() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders").header("X-API-Key", "wrong-key"));
    var chain = mock(WebFilterChain.class);

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verify(chain, never()).filter(any());
  }

  @Test
  void allowsExcludedActuator() {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
    var chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    verify(chain).filter(exchange);
  }

  @Test
  void allowsExcludedSwagger() {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/swagger-ui/index.html"));
    var chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    verify(chain).filter(exchange);
  }

  @Test
  void rejectsWhenKeyNotConfigured() {
    var props = new SecurityProperties("", "X-API-Key", "apiKey", List.of("/actuator/**"));
    var unconfigured = new ApiKeyAuthenticationFilter(props);
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders").header("X-API-Key", "anything"));
    var chain = mock(WebFilterChain.class);

    StepVerifier.create(unconfigured.filter(exchange, chain)).verifyComplete();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verify(chain, never()).filter(any());
  }

  @Test
  void preferHeaderOverQueryParam() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders?apiKey=wrong")
                .header("X-API-Key", "secret-key"));
    var chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    verify(chain).filter(exchange);
  }
}
