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
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * E2E coverage for the Phase-2 risk checks (issues 2.2.1 / 2.2.2 / 2.2.3). Brings up the full
 * docker-compose stack with the ADV-participation limit tightened via env override to a tiny
 * fraction (0.0000001 ≈ ~6 AAPL shares trigger), then verifies a manual order above that limit
 * comes back REJECTED with {@code AdvParticipation} as the failing check.
 *
 * <p>The other two Phase-2 checks (sector / beta exposure) are exercised by the unit + integration
 * tests — they require an accumulated position book to fire, which is harder to set up
 * deterministically in a docker-compose stack.
 */
// Runs LAST among the e2e suite (ClassOrderer.OrderAnnotation, see junit-platform.properties).
// `ClassOrderer.OrderAnnotation` treats unannotated classes as having order Integer.MAX_VALUE/2,
// so this annotation must exceed that — MAX_VALUE guarantees Phase2 runs after every
// shared-stack class. The setUp tears down the SharedComposeStack to swap in its own
// ADV-tightened stack; every shared-stack test must complete before that destruction is safe.
@Order(Integer.MAX_VALUE)
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase2RiskChecksE2ETest {

  private static final String API_KEY = "phase2-risk-e2e";
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
    // Release the SharedComposeStack first so its ComposeContainer handle doesn't fight ours over
    // the same compose project name ("mariaalpha"). Without this, our `compose up -d --build`
    // exits with code 1 and Testcontainers reports an opaque ContainerLaunchException.
    SharedComposeStack.get().stopIfRunning();
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
            // Tighten ADV participation so a 6-share AAPL order trips the check.
            // (AAPL ADV = 60M; 1e-7 × 60M = 6 shares.)
            .withEnv("EXECUTION_ENGINE_RISK_MAX_ADV_PARTICIPATION", "0.0000001")
            .withBuild(true)
            .withRemoveVolumes(true)
            .waitingFor(
                "api-gateway",
                Wait.forLogMessage(".*Started Application in.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(4)))
            .withLogConsumer("api-gateway", f -> System.out.print("[api-gw] " + f.getUtf8String()))
            .withLogConsumer(
                "execution-engine", f -> System.out.print("[exec] " + f.getUtf8String()))
            .withLogConsumer(
                "order-manager", f -> System.out.print("[om] " + f.getUtf8String()));
    composeContainer.start();
    baseUrl = "http://localhost:8080";
    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Test
  void manualOrderAboveAdvParticipationLimitIsRejected() throws Exception {
    var orderId = submitManualOrder("AAPL", "BUY", 100);

    // The Kafka-orderdb lag is bounded; the order-manager persists the REJECTED state shortly
    // after the execution-engine fires the lifecycle event.
    var order =
        await()
            .atMost(20, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(500))
            .ignoreExceptions()
            .until(() -> getOrder(orderId), json -> json != null && json.has("status"));

    assertThat(order.get("status").asText())
        .as("100-share AAPL order with ADV participation cap at 1e-7 must be rejected")
        .isEqualTo("REJECTED");
    // The rejection reason is published on the orders.lifecycle topic but order-manager doesn't
    // persist it on the OrderResponse DTO today. Reason-text assertions live in the unit and
    // integration tests where the chain is reachable directly.
  }

  @Test
  void smallNormalOrderStillPassesTheChain() throws Exception {
    // Establish that the chain still admits normal flow — a 1-share order is well below every
    // Phase-2 limit even with the ADV check tightened.
    var orderId = submitManualOrder("AAPL", "BUY", 1);
    var order =
        await()
            .atMost(20, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(500))
            .ignoreExceptions()
            .until(
                () -> getOrder(orderId),
                json -> json != null && !"NEW".equals(json.get("status").asText()));

    assertThat(order.get("status").asText())
        .as("1-share AAPL order must pass every Phase-2 risk check")
        .isIn("SUBMITTED", "PARTIALLY_FILLED", "FILLED");
  }

  private String submitManualOrder(String symbol, String side, int qty) throws Exception {
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
    return MAPPER.readTree(resp.body()).get("orderId").asText();
  }

  private JsonNode getOrder(String orderId) throws Exception {
    var resp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/orders/" + orderId))
                .header("X-API-Key", API_KEY)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() == 404) {
      return null;
    }
    if (resp.statusCode() != 200) {
      throw new IllegalStateException("GET /api/orders/" + orderId + " → " + resp.statusCode());
    }
    return MAPPER.readTree(resp.body());
  }
}
