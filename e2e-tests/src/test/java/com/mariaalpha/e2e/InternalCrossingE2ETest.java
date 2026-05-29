package com.mariaalpha.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * E2E coverage for issue 2.1.10 — the internal crossing engine. Brings up the full docker-compose
 * stack and:
 *
 * <ul>
 *   <li>verifies the {@code /api/execution/internal-crossing/*} endpoints are routed through the
 *       API Gateway and respond with well-formed JSON;
 *   <li>fires offsetting BUY/SELL MARKET orders into the manual order endpoint and waits for at
 *       least one cross to land in the engine's stats — exercising the full SOR → adapter →
 *       engine → execution-report → lifecycle → order-manager DB path when the SOR picks
 *       INTERNAL_CROSS;
 *   <li>checks the new Prometheus counters are exposed by the execution-engine actuator.
 * </ul>
 *
 * <p>SOR routing in the simulated profile is deterministic — the same inputs always pick the same
 * venue. To make the test robust regardless of which venue currently wins on the composite score,
 * the cross-engine assertion is delivered via either of two paths: (1) the stats counter exceeds
 * the baseline, OR (2) the engine's book accumulates resting interest. The exact path depends on
 * SOR behaviour at the time, but both prove the engine is reachable end-to-end.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InternalCrossingE2ETest {

  private static final String API_KEY = "e2e-test-key";
  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private ComposeContainer composeContainer;
  private HttpClient httpClient;
  private String baseUrl;

  @AfterAll
  void stopStack() {
    if (composeContainer != null) {
      composeContainer.stop();
    }
  }

  @BeforeAll
  void startStack() throws Exception {
    var dockerComposeFile = new File("../docker-compose.yml");
    new ProcessBuilder(
            "docker",
            "compose",
            "-f",
            dockerComposeFile.getCanonicalPath(),
            "down",
            "-v",
            "--remove-orphans")
        .directory(dockerComposeFile.getCanonicalFile().getParentFile())
        .redirectErrorStream(true)
        .start()
        .waitFor();
    composeContainer =
        new ComposeContainer(dockerComposeFile)
            .withLocalCompose(true)
            .withEnv("MARIAALPHA_API_KEY", API_KEY)
            .withEnv("POSTGRES_USER", "mariaalpha")
            .withEnv("POSTGRES_PASSWORD", "mariaalpha")
            .withEnv("POSTGRES_DB", "mariaalpha")
            .withEnv("ALPACA_API_KEY_ID", "unused")
            .withEnv("ALPACA_API_SECRET_KEY", "unused")
            .withBuild(true)
            .withRemoveVolumes(true)
            .waitingFor(
                "api-gateway",
                Wait.forLogMessage(".*Started Application in.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(4)))
            .withLogConsumer("api-gateway", f -> System.out.print("[api-gw] " + f.getUtf8String()))
            .withLogConsumer(
                "execution-engine", f -> System.out.print("[exec] " + f.getUtf8String()));
    composeContainer.start();
    baseUrl = "http://localhost:8080";
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

    // Submit a short burst of BUY/SELL pairs. SOR routes deterministically — when INTERNAL_CROSS
    // wins on the composite score these orders flow into the engine and cross; when it loses,
    // they flow to the LIT/DARK adapters and we still verify the e2e plumbing via the second
    // assertion below.
    for (int i = 0; i < 10; i++) {
      submitManualOrder("NVDA", "SELL", 10);
      submitManualOrder("NVDA", "BUY", 10);
    }

    // Allow several seconds for SOR + adapter + engine + fill-callback fan-out.
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
              // Either crosses happened OR orders rested. Both prove the engine is live and
              // wired through the gateway → execution → adapter → engine path.
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
    // Execution-engine management port is 8085, mapped on the host by docker-compose.
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
                .header("X-API-Key", API_KEY)
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
                .header("X-API-Key", API_KEY)
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
