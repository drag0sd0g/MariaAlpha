package com.mariaalpha.strategyengine.rfq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Looks up the desk's current net position in a single symbol from the Order Manager REST API. Used
 * by {@link RfqPricingEngine} to skew the mid for inventory (issue 2.4.1).
 *
 * <p>Falls back to a flat position (zero net quantity) when:
 *
 * <ul>
 *   <li>the Order Manager returns 404 (symbol never traded yet);
 *   <li>the HTTP call times out or errors (so RFQ pricing degrades to symmetric quoting around mid
 *       rather than blocking on a downstream stall).
 * </ul>
 *
 * <p>The 500ms default timeout is small on purpose — RFQ pricing is a foreground UI call and the
 * trader sees the resulting quote within a few seconds.
 */
@Component
public class PositionLookup {

  private static final Logger LOG = LoggerFactory.getLogger(PositionLookup.class);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final RfqPricingConfig config;

  @Autowired
  public PositionLookup(ObjectMapper objectMapper, RfqPricingConfig config) {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.positionLookupTimeoutMs()))
            .build(),
        objectMapper,
        config);
  }

  PositionLookup(HttpClient httpClient, ObjectMapper objectMapper, RfqPricingConfig config) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.config = config;
  }

  public PositionView fetch(String symbol) {
    String url = config.orderManagerBaseUrl() + "/api/positions/" + symbol;
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(config.positionLookupTimeoutMs()))
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        var dto = objectMapper.readValue(response.body(), PositionDto.class);
        return PositionView.of(symbol, dto);
      }
      if (response.statusCode() == 404) {
        return PositionView.flat(symbol);
      }
      LOG.warn(
          "order-manager position lookup for {} returned status {}", symbol, response.statusCode());
      return PositionView.unavailable(symbol);
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.warn("order-manager position lookup for {} failed: {}", symbol, e.getMessage());
      return PositionView.unavailable(symbol);
    }
  }

  /** What the RFQ pricing engine needs to know about a symbol's current position. */
  public record PositionView(
      String symbol, BigDecimal netQuantity, BigDecimal lastMarkPrice, boolean available) {

    static PositionView flat(String symbol) {
      return new PositionView(symbol, BigDecimal.ZERO, BigDecimal.ZERO, true);
    }

    static PositionView unavailable(String symbol) {
      return new PositionView(symbol, BigDecimal.ZERO, BigDecimal.ZERO, false);
    }

    static PositionView of(String symbol, PositionDto dto) {
      return new PositionView(
          symbol,
          Optional.ofNullable(dto.netQuantity()).orElse(BigDecimal.ZERO),
          Optional.ofNullable(dto.lastMarkPrice()).orElse(BigDecimal.ZERO),
          true);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PositionDto(String symbol, BigDecimal netQuantity, BigDecimal lastMarkPrice) {}
}
