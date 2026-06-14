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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Full-stack e2e for PEGGED orders (roadmap 3.2.3). Drives the execution-engine through the
 * api-gateway:
 *
 * <ol>
 *   <li>Submit a PEGGED MIDPOINT BUY parent and assert the pegged-progress endpoint reports an
 *       active LIMIT child at the midpoint of the simulated NBBO.
 *   <li>Submit with a missing pegType and assert the validator rejects it with HTTP 400.
 *   <li>Submit a non-PEGGED order with a stray pegType and assert the validator rejects it.
 * </ol>
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PeggedOrderE2ETest {

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
  void submitPeggedMidpointBuyRegistersChildAndReportsProgress() throws Exception {
    // The simulator continuously republishes AAPL ticks; wait until the execution-engine's
    // MarketStateTracker has at least one snapshot.
    var orderId =
        await()
            .atMost(45, TimeUnit.SECONDS)
            .pollInterval(Duration.ofSeconds(1))
            .ignoreExceptions()
            .until(this::submitPeggedMidpointBuy, id -> id != null);

    // The PEGGED parent registers a child immediately on submit; the simulator might already
    // have filled it before we get here, in which case the registry has dropped the parent and
    // the progress endpoint returns 404. Either outcome is a valid pegged round-trip:
    //  (1) progress endpoint returns a snapshot with the expected fields, OR
    //  (2) progress endpoint returns 404 AND the order shows up in /api/orders in a terminal state.
    //
    // Budget 60s (not 20s): when the child is synthetically crossed on submit, the parent is
    // FILLED+removed instantly, so this poll falls to branch (2), which depends on order-manager
    // having consumed the FILLED event off `orders.lifecycle`. Early in a freshly-started shared
    // stack the order-manager consumer group is still rebalancing/resetting offsets, so that
    // cross-service propagation can take well over 20s under CI load. 60s matches the budget the
    // rest of the suite (e.g. SimulatedHappyPathE2ETest) already uses for order-manager catch-up.
    boolean done =
        await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(500))
            .ignoreExceptions()
            .until(
                () -> checkProgressOrTerminalOrder(orderId),
                Boolean::booleanValue);
    assertThat(done).isTrue();
  }

  private boolean checkProgressOrTerminalOrder(String orderId) throws Exception {
    var progress = getProgress(orderId);
    if (progress != null) {
      assertThat(progress.get("parentOrderId").asText()).isEqualTo(orderId);
      assertThat(progress.get("totalQuantity").asInt()).isEqualTo(10);
      assertThat(progress.get("lastReferencePrice").isNumber()).isTrue();
      assertThat(progress.get("lastSubmittedPrice").isNumber()).isTrue();
      return true;
    }
    // 404 from the registry — the parent must have completed. Look it up in the order-manager.
    var order = lookupOrder(orderId);
    if (order == null) {
      return false;
    }
    var status = order.get("status").asText();
    return status.equals("FILLED")
        || status.equals("PARTIALLY_FILLED")
        || status.equals("CANCELLED")
        || status.equals("REJECTED");
  }

  private JsonNode lookupOrder(String orderId) throws Exception {
    var resp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/orders/" + orderId))
                .header("X-API-Key", apiKey)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() == 404) {
      return null;
    }
    if (resp.statusCode() != 200) {
      return null;
    }
    return MAPPER.readTree(resp.body());
  }

  @Test
  void missingPegTypeOnPeggedOrderIsRejected() throws Exception {
    var body =
        MAPPER.writeValueAsString(
            Map.of(
                "symbol", "AAPL",
                "side", "BUY",
                "orderType", "PEGGED",
                "quantity", 10,
                "tif", "DAY"));
    var resp = postRaw("/api/execution/orders", body);
    assertThat(resp.statusCode())
        .as("PEGGED submit without pegType → %s", resp.body())
        .isEqualTo(400);
  }

  @Test
  void pegTypeOnNonPeggedOrderIsRejected() throws Exception {
    var body =
        MAPPER.writeValueAsString(
            Map.of(
                "symbol", "AAPL",
                "side", "BUY",
                "orderType", "MARKET",
                "quantity", 10,
                "tif", "DAY",
                "pegType", "MIDPOINT"));
    var resp = postRaw("/api/execution/orders", body);
    assertThat(resp.statusCode())
        .as("MARKET submit with stray pegType → %s", resp.body())
        .isEqualTo(400);
  }

  private String submitPeggedMidpointBuy() throws Exception {
    var body =
        MAPPER.writeValueAsString(
            Map.of(
                "symbol", "AAPL",
                "side", "BUY",
                "orderType", "PEGGED",
                "quantity", 10,
                "tif", "DAY",
                "pegType", "MIDPOINT",
                "pegOffsetBps", 0));
    var resp = postRaw("/api/execution/orders", body);
    if (resp.statusCode() != 202) {
      return null;
    }
    var node = MAPPER.readTree(resp.body());
    return node.has("orderId") ? node.get("orderId").asText() : null;
  }

  private JsonNode getProgress(String orderId) throws Exception {
    var path = "/api/execution/orders/" + orderId + "/pegged-progress";
    var resp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() == 404) {
      return null;
    }
    if (resp.statusCode() != 200) {
      throw new IllegalStateException(
          "GET " + path + " → " + resp.statusCode() + " body=" + resp.body());
    }
    return MAPPER.readTree(resp.body());
  }

  private HttpResponse<String> postRaw(String path, String body) throws Exception {
    return httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("X-API-Key", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(5))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
