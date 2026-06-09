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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Full-stack e2e for the trade-allocation engine (roadmap 3.4.2). Drives the post-trade allocation
 * REST surface through the api-gateway against the shared docker-compose stack. The compose stack
 * configures the default roster (HOUSE 50 / HEDGE_FUND_A 30 / HEDGE_FUND_B 20) so the assertions
 * below are deterministic.
 *
 * <ol>
 *   <li>{@code GET /api/allocations/sub-accounts} — list the configured roster.
 *   <li>{@code POST /api/allocations/run} — allocate 1000 shares pro-rata → 500/300/200.
 *   <li>{@code GET /api/allocations/order/{orderId}} — reads back the same rows.
 *   <li>{@code GET /api/allocations/sub-account/HOUSE} — finds at least one row for HOUSE.
 *   <li>Re-running with FIFO is idempotent — the prior rows are cleared.
 * </ol>
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradeAllocationE2ETest {

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
  void subAccountsListReturnsConfiguredRoster() throws Exception {
    var roster = httpGet("/api/allocations/sub-accounts");
    assertThat(roster.get("defaultMethod").asText()).isEqualTo("PRO_RATA");
    var accounts = roster.get("subAccounts");
    assertThat(accounts.size()).isEqualTo(3);
    assertThat(accounts.get(0).get("name").asText()).isEqualTo("HOUSE");
    assertThat(accounts.get(1).get("name").asText()).isEqualTo("HEDGE_FUND_A");
    assertThat(accounts.get(2).get("name").asText()).isEqualTo("HEDGE_FUND_B");
  }

  @Test
  void proRataAllocationIsPersistedAndQueryable() throws Exception {
    var orderId = UUID.randomUUID();
    var body =
        MAPPER.writeValueAsString(
            Map.of(
                "orderId", orderId.toString(),
                "symbol", "AAPL",
                "side", "BUY",
                "parentFilledQuantity", 1000,
                "parentAvgPrice", 178.42));
    var created = httpPostExpect(201, "/api/allocations/run", body);
    assertThat(created.size()).isEqualTo(3);

    // Sum of allocated qty == parent qty.
    int sum = 0;
    for (var node : created) {
      sum += node.get("allocatedQuantity").asInt();
    }
    assertThat(sum).isEqualTo(1000);

    var byOrder = httpGet("/api/allocations/order/" + orderId);
    assertThat(byOrder.size()).isEqualTo(3);

    var bySub = httpGet("/api/allocations/sub-account/HOUSE");
    assertThat(bySub.size()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void reAllocationIsIdempotentOnOrderId() throws Exception {
    var orderId = UUID.randomUUID();
    var bodyProRata =
        MAPPER.writeValueAsString(
            Map.of(
                "orderId", orderId.toString(),
                "symbol", "AAPL",
                "side", "BUY",
                "parentFilledQuantity", 100,
                "parentAvgPrice", 178.42,
                "method", "PRO_RATA"));
    var first = httpPostExpect(201, "/api/allocations/run", bodyProRata);
    assertThat(first.size()).isEqualTo(3);

    var bodyFifo =
        MAPPER.writeValueAsString(
            Map.of(
                "orderId", orderId.toString(),
                "symbol", "AAPL",
                "side", "BUY",
                "parentFilledQuantity", 25,
                "parentAvgPrice", 178.42,
                "method", "FIFO"));
    var second = httpPostExpect(201, "/api/allocations/run", bodyFifo);
    // FIFO with 25 shares → only HOUSE filled (weight 50 ≥ 25).
    assertThat(second.size()).isEqualTo(1);

    var current = httpGet("/api/allocations/order/" + orderId);
    assertThat(current.size())
        .as("Idempotent re-allocation must clear prior rows before persisting new ones")
        .isEqualTo(1);
    assertThat(current.get(0).get("allocationMethod").asText()).isEqualTo("FIFO");
  }

  @Test
  void invalidParentQuantityIsRejectedAs400() throws Exception {
    var body =
        MAPPER.writeValueAsString(
            Map.of(
                "orderId", UUID.randomUUID().toString(),
                "symbol", "AAPL",
                "side", "BUY",
                "parentFilledQuantity", 0,
                "parentAvgPrice", 178.42));
    var resp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/allocations/run"))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(400);
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
    assertThat(resp.statusCode())
        .as("GET %s → %s", path, resp.body())
        .isEqualTo(200);
    return MAPPER.readTree(resp.body());
  }

  private JsonNode httpPostExpect(int expectedStatus, String path, String body) throws Exception {
    var resp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode())
        .as("POST %s body=%s → %s", path, body, resp.body())
        .isEqualTo(expectedStatus);
    return MAPPER.readTree(resp.body());
  }
}
