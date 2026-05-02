package com.mariaalpha.apigateway.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Implemented as a {@link WebFilter} (not a Spring Cloud Gateway {@code GlobalFilter}) so it
 * intercepts every inbound HTTP request, including WebSocket upgrade {@code GET}s served by our own
 * {@code SimpleUrlHandlerMapping}. {@code GlobalFilter}s only run for routes resolved by {@code
 * RoutePredicateHandlerMapping}, which would let WS handshakes bypass auth.
 */
@Component
public class ApiKeyAuthenticationFilter implements WebFilter, Ordered {

  /** Run before any handler mapping so we never open a backend connection on a 401. */
  public static final int ORDER = -100;

  private static final Logger LOG = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

  /**
   * AntPathMatcher is used here because it's designed specifically for path pattern matching rather
   * than general-purpose regex
   */
  private static final AntPathMatcher MATCHER = new AntPathMatcher();

  private static final String UNAUTHORIZED_BODY =
      "{\"status\":401,\"error\":\"Unauthorized\",\"detail\":\"Missing or invalid API key\"}";

  private static final String UNCONFIGURED_BODY =
      "{\"status\":401,\"error\":\"Unauthorized\","
          + "\"detail\":\"Gateway API key is not configured\"}";

  private final SecurityProperties properties;

  public ApiKeyAuthenticationFilter(SecurityProperties properties) {
    this.properties = properties;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    var request = exchange.getRequest();
    var path = request.getPath().value();
    if (isExcluded(path)) {
      return chain.filter(exchange);
    }
    if (!properties.isConfigured()) {
      LOG.error("API key auth is not configured — rejecting request to {}", path);
      return reject(exchange, UNCONFIGURED_BODY);
    }
    var presented = extractKey(request);
    if (ApiKeyMatcher.matches(properties.apiKey(), presented)) {
      LOG.debug("auth passed for path {}", path);
      return chain.filter(exchange);
    }
    LOG.warn(
        "auth for path {} failed (header_present={}, query_param_present={})",
        path,
        presented != null
            && exchange.getRequest().getHeaders().containsKey(properties.headerName()),
        exchange.getRequest().getQueryParams().containsKey(properties.queryParamName()));
    return reject(exchange, UNAUTHORIZED_BODY);
  }

  private Mono<Void> reject(ServerWebExchange exchange, String body) {
    var response = exchange.getResponse();
    response.setStatusCode(HttpStatus.UNAUTHORIZED);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    response.getHeaders().add(HttpHeaders.WWW_AUTHENTICATE, "ApiKey");
    return response.writeWith(
        Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
  }

  private String extractKey(ServerHttpRequest request) {
    var header = request.getHeaders().getFirst(properties.headerName());
    if (header != null && !header.isEmpty()) {
      return header;
    }
    return request.getQueryParams().getFirst(properties.queryParamName());
  }

  private boolean isExcluded(String path) {
    List<String> excluded = properties.excludedPaths();
    if (excluded == null) {
      return false;
    }
    for (String pattern : excluded) {
      if (MATCHER.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }
}
