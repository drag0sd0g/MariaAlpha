package com.mariaalpha.posttrade.recon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.posttrade.model.Side;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpAlpacaActivitiesClient implements AlpacaActivitiesClient {

  private static final Logger LOG = LoggerFactory.getLogger(HttpAlpacaActivitiesClient.class);

  private final ReconConfig.Alpaca alpacaConfig;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public HttpAlpacaActivitiesClient(ReconConfig.Alpaca alpacaConfig, ObjectMapper objectMapper) {
    this.alpacaConfig = alpacaConfig;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(alpacaConfig.httpTimeoutMs()))
            .build();
  }

  @Override
  public List<ExternalFill> activitiesForDate(LocalDate date, List<InternalFill> internalFills) {
    if (alpacaConfig.apiKey() == null
        || alpacaConfig.apiKey().isBlank()
        || alpacaConfig.apiSecret() == null
        || alpacaConfig.apiSecret().isBlank()) {
      throw new AlpacaActivitiesException(
          "Alpaca credentials not configured — set ALPACA_API_KEY_ID / ALPACA_API_SECRET_KEY");
    }
    String url =
        alpacaConfig.baseUrl()
            + "/v2/account/activities?activity_types="
            + alpacaConfig.activityTypes()
            + "&date="
            + date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("APCA-API-KEY-ID", alpacaConfig.apiKey())
            .header("APCA-API-SECRET-KEY", alpacaConfig.apiSecret())
            .header("Accept", "application/json")
            .GET()
            .timeout(Duration.ofMillis(alpacaConfig.httpTimeoutMs()))
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new AlpacaActivitiesException(
            "Alpaca activities GET returned " + response.statusCode() + ": " + response.body());
      }
      return parseActivities(response.body());
    } catch (AlpacaActivitiesException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AlpacaActivitiesException("Interrupted calling Alpaca activities API", e);
    } catch (Exception e) {
      throw new AlpacaActivitiesException("Alpaca activities call failed: " + e.getMessage(), e);
    }
  }

  List<ExternalFill> parseActivities(String body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (!root.isArray()) {
        return List.of();
      }
      List<ExternalFill> out = new ArrayList<>(root.size());
      for (JsonNode node : root) {
        ExternalFill fill = parseOne(node);
        if (fill != null) {
          out.add(fill);
        }
      }
      return out;
    } catch (Exception e) {
      throw new AlpacaActivitiesException("Failed to parse Alpaca activities response", e);
    }
  }

  private ExternalFill parseOne(JsonNode node) {
    try {
      String id = textOrNull(node, "id");
      String exchangeOrderId = textOrNull(node, "order_id");
      String clientOrderId = textOrNull(node, "client_order_id");
      String symbol = textOrNull(node, "symbol");
      String sideText = textOrNull(node, "side");
      String priceText = textOrNull(node, "price");
      String qtyText = textOrNull(node, "qty");
      String txTime = textOrNull(node, "transaction_time");
      if (symbol == null || priceText == null || qtyText == null) {
        return null;
      }
      Side side = sideText == null ? null : mapSide(sideText);
      return new ExternalFill(
          id,
          exchangeOrderId,
          clientOrderId,
          symbol,
          side,
          new java.math.BigDecimal(priceText),
          new java.math.BigDecimal(qtyText),
          txTime == null ? null : OffsetDateTime.parse(txTime).toInstant());
    } catch (Exception e) {
      LOG.warn("Skipping malformed Alpaca activity node: {}", node, e);
      return null;
    }
  }

  private static Side mapSide(String s) {
    return switch (s.toUpperCase()) {
      case "BUY" -> Side.BUY;
      case "SELL", "SELL_SHORT" -> Side.SELL;
      default -> null;
    };
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }
}
