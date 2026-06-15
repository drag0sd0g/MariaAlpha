package com.mariaalpha.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InternalCrossingE2ETest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private final String apiKey = SharedComposeStack.get().apiKey();
  private final String baseUrl = SharedComposeStack.get().gatewayBaseUrl();
  private HttpClient httpClient;

  @BeforeAll
  void startStack() {
    SharedComposeStack.get().start();
    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Test
  void internalCrossingEndpointsAreReachableViaApiGateway() throws Exception {
    var stats = httpGet("/api/execution/internal-crossing/stats");
    assertThat(stats.has("crossesTotal")).isTrue();
    assertThat(stats.has("internalCrossesTotal")).isTrue();
    assertThat(stats.has("syntheticCrossesTotal")).isTrue();
    assertThat(stats.has("sharesCrossedTotal")).isTrue();
    assertThat(stats.has("spreadCapturedNotional")).isTrue();
    assertThat(stats.has("restingOrdersBuy")).isTrue();
    assertThat(stats.has("restingOrdersSell")).isTrue();

    var book = httpGet("/api/execution/internal-crossing/book");
    assertThat(book.isObject()).isTrue();

    var recent = httpGet("/api/execution/internal-crossing/recent");
    assertThat(recent.isArray()).isTrue();
  }

  @Test
  void offsettingManualOrdersProduceActivityOnInternalVenue() throws Exception {
    var statsBefore = httpGet("/api/execution/internal-crossing/stats");
    long crossesBefore = statsBefore.get("crossesTotal").asLong();

    for (int i = 0; i < 10; i++) {
      submitManualOrder("NVDA", "SELL", 10);
      submitManualOrder("NVDA", "BUY", 10);
    }

    await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(Duration.ofSeconds(1))
        .ignoreExceptions()
        .untilAsserted(
            () -> {
              var statsAfter = httpGet("/api/execution/internal-crossing/stats");
              long crossesAfter = statsAfter.get("crossesTotal").asLong();
              int restingTotal =
                  statsAfter.get("restingOrdersBuy").asInt()
                      + statsAfter.get("restingOrdersSell").asInt();
              assertThat(crossesAfter > crossesBefore || restingTotal > 0)
                  .as(
                      "expected SOR/engine activity to either cross orders or accumulate resting"
                          + " interest after offsetting submits (crossesBefore=%d, crossesAfter=%d,"
                          + " restingTotal=%d)",
                      crossesBefore, crossesAfter, restingTotal)
                  .isTrue();
            });
  }

  @Test
  void prometheusEndpointExposesInternalCrossingCounters() throws Exception {
    var prom = httpGetRaw("http://localhost:8085/actuator/prometheus");
    assertThat(prom.statusCode()).isEqualTo(200);
    assertThat(prom.body())
        .as("Prometheus scrape should include the new internal-crossing meters")
        .contains("mariaalpha_execution_internal_book_buy_depth")
        .contains("mariaalpha_execution_internal_book_sell_depth")
        .contains("mariaalpha_execution_internal_book_resting_orders");
  }

  private void submitManualOrder(String symbol, String side, int qty) throws Exception {
    var body =
        MAPPER.writeValueAsString(
            java.util.Map.of(
                "symbol", symbol,
                "side", side,
                "orderType", "MARKET",
                "quantity", qty,
                "tif", "DAY"));
    var resp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/execution/orders"))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode())
        .as("POST /api/execution/orders %s/%s → %s", symbol, side, resp.body())
        .isEqualTo(202);
  }

  private JsonNode httpGet(String path) throws Exception {
    var resp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).as("GET %s → %s", path, resp.body()).isEqualTo(200);
    return MAPPER.readTree(resp.body());
  }

  private HttpResponse<String> httpGetRaw(String url) throws Exception {
    return httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
