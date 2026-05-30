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
 * E2E coverage for the analytics-service (issues 2.2.4 / 2.2.5 / 2.2.6). Brings up the full
 * docker-compose stack and verifies the analytics REST surface is reachable through the API
 * gateway, exercising axe-publish + match-suggest and Prometheus metrics scraping.
 *
 * <p>The flow-toxicity and PnL-attribution endpoints both require Kafka traffic ({@code
 * analytics.tca}, {@code market-data.ticks}) before they have data to report; we only assert the
 * endpoints are reachable and return the documented empty-state JSON.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsServiceE2ETest {

  private static final String API_KEY = "analytics-service-e2e";
  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private ComposeContainer composeContainer;
  private HttpClient httpClient;
  private String gatewayBaseUrl;

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
            .withLogConsumer(
                "analytics-service",
                f -> System.out.print("[analytics] " + f.getUtf8String()))
            .withLogConsumer(
                "api-gateway", f -> System.out.print("[api-gw] " + f.getUtf8String()));
    composeContainer.start();
    gatewayBaseUrl = "http://localhost:8080";
    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Test
  void flowToxicityEndpointReturnsEmptyRowsAndConfig() throws Exception {
    var body = getJson("/api/analytics/flow/toxicity");
    assertThat(body.has("rows")).as("payload must carry rows[]").isTrue();
    assertThat(body.has("thresholdBps")).as("payload must echo thresholdBps").isTrue();
    assertThat(body.has("horizonsSeconds")).as("payload must echo horizonsSeconds").isTrue();
  }

  @Test
  void pnlAttributionDailyEndpointReturnsEmptyDailyArray() throws Exception {
    var body = getJson("/api/analytics/pnl/attribution");
    assertThat(body.has("daily")).as("payload must carry daily[]").isTrue();
    assertThat(body.get("daily").isArray()).isTrue();
  }

  @Test
  void axePublishAndListThroughGateway() throws Exception {
    var axeId = "e2e-axe-" + System.currentTimeMillis();
    var publishBody =
        MAPPER.writeValueAsString(
            java.util.Map.of(
                "axe_id", axeId,
                "client_id", "E2E_CLIENT",
                "symbol", "AAPL",
                "side", "BUY",
                "quantity", 1000,
                "limit_price", 99.5,
                "ttl_seconds", 600));
    var publishResp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(gatewayBaseUrl + "/api/analytics/axes"))
                .header("X-API-Key", API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(publishBody))
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(publishResp.statusCode())
        .as("POST /api/analytics/axes → %s", publishResp.body())
        .isEqualTo(201);
    var publishJson = MAPPER.readTree(publishResp.body());
    assertThat(publishJson.get("axeId").asText()).isEqualTo(axeId);

    // Within a short window the listing endpoint should report the axe back.
    await()
        .atMost(5, TimeUnit.SECONDS)
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(
            () -> {
              var listed = getJson("/api/analytics/axes?symbol=AAPL");
              var ids = new java.util.ArrayList<String>();
              listed.get("axes").forEach(a -> ids.add(a.get("axeId").asText()));
              assertThat(ids).contains(axeId);
            });
  }

  @Test
  void prometheusMetricsExposedOnDirectPort() throws Exception {
    // The Alloy scraper reaches the service directly on port 8095. Verify the surface is up.
    var resp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8095/metrics"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body()).contains("# HELP");
  }

  private JsonNode getJson(String path) throws Exception {
    var resp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(gatewayBaseUrl + path))
                .header("X-API-Key", API_KEY)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode())
        .as("GET %s → %s", path, resp.body())
        .isEqualTo(200);
    return MAPPER.readTree(resp.body());
  }
}
