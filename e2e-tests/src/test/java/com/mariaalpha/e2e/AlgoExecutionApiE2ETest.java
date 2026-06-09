package com.mariaalpha.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Exercises the algorithmic-execution REST surface (roadmap 3.4.4): submit a parent order
 * programmatically, query it back, list, and cancel it. Lightweight on assertions — the wired
 * unit + controller tests cover the request/response shape; this test class proves the
 * end-to-end gateway → strategy-engine route + JSON serialisation work in the running stack.
 *
 * <p>Note: filled-quantity progress is intentionally not asserted here — that requires algoOrderId
 * propagation through the signal → execution → order-manager chain, which is a future-work item
 * captured in docs/strategies/algo-execution-api.md.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlgoExecutionApiE2ETest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private final String apiKey = SharedComposeStack.get().apiKey();
  private final String baseUrl = SharedComposeStack.get().gatewayBaseUrl();
  private HttpClient httpClient;
  private String createdAlgoOrderId;

  @BeforeAll
  void startStack() {
    SharedComposeStack.get().start();
    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @AfterEach
  void cleanup() throws Exception {
    if (createdAlgoOrderId != null) {
      // Best-effort cancel so a subsequent test (or test re-run) doesn't see a leftover binding.
      httpClient.send(
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/algo/orders/" + createdAlgoOrderId))
              .header("X-API-Key", apiKey)
              .DELETE()
              .timeout(Duration.ofSeconds(5))
              .build(),
          HttpResponse.BodyHandlers.discarding());
      createdAlgoOrderId = null;
    }
    // Also unbind any symbol an algo might have routed (mirrors the SimulatedHappyPath pattern).
    for (String symbol : new String[] {"AAPL", "MSFT", "GOOGL", "AMZN", "TSLA", "NVDA"}) {
      try {
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/strategies/" + symbol))
                .header("X-API-Key", apiKey)
                .DELETE()
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.discarding());
      } catch (Exception ignored) {
        // best-effort cleanup
      }
    }
  }

  @Test
  void submitListGetAndCancelAlgoOrderRoundTripsThroughGateway() throws Exception {
    var requestBody =
        "{"
            + "\"symbol\":\"MSFT\","
            + "\"side\":\"BUY\","
            + "\"targetQuantity\":50,"
            + "\"strategyName\":\"TWAP\","
            + "\"parameters\":{"
            + "  \"targetQuantity\":50,"
            + "  \"side\":\"BUY\","
            + "  \"startTime\":\"10:30:00\","
            + "  \"endTime\":\"10:31:00\","
            + "  \"numSlices\":1"
            + "}"
            + "}";

    var submitted = httpPost("/api/algo/orders", requestBody, 201);
    assertThat(submitted.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(submitted.get("symbol").asText()).isEqualTo("MSFT");
    assertThat(submitted.get("targetQuantity").asLong()).isEqualTo(50);
    assertThat(submitted.get("strategyName").asText()).isEqualTo("TWAP");
    createdAlgoOrderId = submitted.get("algoOrderId").asText();

    // GET round-trips the same record.
    var fetched = httpGet("/api/algo/orders/" + createdAlgoOrderId, 200);
    assertThat(fetched.get("algoOrderId").asText()).isEqualTo(createdAlgoOrderId);
    assertThat(fetched.get("status").asText()).isEqualTo("ACTIVE");

    // LIST contains the freshly-created order.
    var list = httpGet("/api/algo/orders", 200);
    assertThat(list.isArray()).isTrue();
    boolean found = false;
    for (var entry : list) {
      if (createdAlgoOrderId.equals(entry.get("algoOrderId").asText())) {
        found = true;
        break;
      }
    }
    assertThat(found).as("listed algo orders must include the just-submitted one").isTrue();

    // DELETE transitions to CANCELLED.
    var cancelled = httpDelete("/api/algo/orders/" + createdAlgoOrderId, 200);
    assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
    createdAlgoOrderId = null; // already cancelled; the @AfterEach cleanup can be skipped.
  }

  @Test
  void submitReturns400ForUnknownStrategy() throws Exception {
    var body =
        "{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"targetQuantity\":100,\"strategyName\":\"DEFINITELY_NOT_A_STRATEGY\"}";
    var response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/algo/orders"))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void getReturns404ForUnknownAlgoOrderId() throws Exception {
    var response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        baseUrl
                            + "/api/algo/orders/00000000-0000-0000-0000-000000000000"))
                .header("X-API-Key", apiKey)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(404);
  }

  private JsonNode httpPost(String path, String body, int expectedStatus) throws Exception {
    var response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("POST %s → %d / body=%s", path, response.statusCode(), response.body())
        .isEqualTo(expectedStatus);
    return MAPPER.readTree(response.body());
  }

  private JsonNode httpGet(String path, int expectedStatus) throws Exception {
    var response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("GET %s → %d / body=%s", path, response.statusCode(), response.body())
        .isEqualTo(expectedStatus);
    return MAPPER.readTree(response.body());
  }

  private JsonNode httpDelete(String path, int expectedStatus) throws Exception {
    var response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .DELETE()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("DELETE %s → %d / body=%s", path, response.statusCode(), response.body())
        .isEqualTo(expectedStatus);
    return MAPPER.readTree(response.body());
  }
}
