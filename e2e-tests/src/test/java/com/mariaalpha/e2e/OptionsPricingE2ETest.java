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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Full-stack e2e for the options pricing endpoints (roadmap 3.2.1 / 3.2.2). Drives
 * {@code POST /api/options/{price,greeks,implied-volatility}} through the api-gateway against the
 * shared docker-compose stack. The endpoints are stateless and don't depend on the simulator's
 * market-data flow, so this test can attach to the shared stack without any per-test setup.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OptionsPricingE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

  private final String apiKey = SharedComposeStack.get().apiKey();
  private final String baseUrl = SharedComposeStack.get().gatewayBaseUrl();
  private HttpClient httpClient;

  @BeforeAll
  void startStack() {
    SharedComposeStack.get().start();
    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Test
  void priceEndpointReturnsTextbookHullValueThroughGateway() throws Exception {
    var body =
        "{\"symbol\":\"AAPL\",\"spot\":42.0,\"strike\":40.0,\"timeToExpiryYears\":0.5,"
            + "\"volatility\":0.20,\"riskFreeRate\":0.10,\"dividendYield\":0.0,\"type\":\"CALL\"}";
    var resp = postJson("/api/options/price", body);
    assertThat(resp.get("symbol").asText()).isEqualTo("AAPL");
    assertThat(resp.get("type").asText()).isEqualTo("CALL");
    assertThat(resp.get("price").asDouble()).isCloseTo(4.76, org.assertj.core.data.Offset.offset(0.01));
    assertThat(resp.get("greeks").get("delta").asDouble())
        .isCloseTo(0.7791, org.assertj.core.data.Offset.offset(0.001));
    assertThat(resp.get("greeks").get("gamma").asDouble())
        .isCloseTo(0.0498, org.assertj.core.data.Offset.offset(0.001));
    assertThat(resp.get("greeks").get("vega").asDouble())
        .isCloseTo(0.0879, org.assertj.core.data.Offset.offset(0.001));
    assertThat(resp.get("greeks").get("theta").asDouble())
        .isCloseTo(-0.01247, org.assertj.core.data.Offset.offset(0.001));
    assertThat(resp.get("greeks").get("rho").asDouble())
        .isCloseTo(0.1398, org.assertj.core.data.Offset.offset(0.001));
  }

  @Test
  void putCallParityHoldsThroughGateway() throws Exception {
    var callBody =
        "{\"symbol\":\"AAPL\",\"spot\":100.0,\"strike\":95.0,\"timeToExpiryYears\":1.0,"
            + "\"volatility\":0.30,\"riskFreeRate\":0.04,\"dividendYield\":0.0,\"type\":\"CALL\"}";
    var putBody =
        "{\"symbol\":\"AAPL\",\"spot\":100.0,\"strike\":95.0,\"timeToExpiryYears\":1.0,"
            + "\"volatility\":0.30,\"riskFreeRate\":0.04,\"dividendYield\":0.0,\"type\":\"PUT\"}";
    double callPx = postJson("/api/options/price", callBody).get("price").asDouble();
    double putPx = postJson("/api/options/price", putBody).get("price").asDouble();
    double parityRhs = 100.0 - 95.0 * Math.exp(-0.04 * 1.0);
    assertThat(callPx - putPx).isCloseTo(parityRhs, org.assertj.core.data.Offset.offset(1e-4));
  }

  @Test
  void impliedVolEndpointInvertsPricerThroughGateway() throws Exception {
    var priceBody =
        "{\"symbol\":\"AAPL\",\"spot\":100.0,\"strike\":100.0,\"timeToExpiryYears\":0.5,"
            + "\"volatility\":0.35,\"riskFreeRate\":0.04,\"dividendYield\":0.0,\"type\":\"CALL\"}";
    double premium = postJson("/api/options/price", priceBody).get("price").asDouble();

    var ivBody =
        "{\"symbol\":\"AAPL\",\"spot\":100.0,\"strike\":100.0,\"timeToExpiryYears\":0.5,"
            + "\"riskFreeRate\":0.04,\"dividendYield\":0.0,\"type\":\"CALL\","
            + "\"marketPrice\":"
            + premium
            + "}";
    var iv = postJson("/api/options/implied-volatility", ivBody);
    assertThat(iv.get("impliedVolatility").asDouble())
        .isCloseTo(0.35, org.assertj.core.data.Offset.offset(1e-4));
    assertThat(iv.get("method").asText()).isIn("NEWTON", "BISECTION");
    assertThat(iv.get("iterations").asInt()).isGreaterThan(0);
  }

  @Test
  void priceEndpointReturns400OnBadInputs() throws Exception {
    var body =
        "{\"symbol\":\"AAPL\",\"spot\":-100.0,\"strike\":40.0,\"timeToExpiryYears\":0.5,"
            + "\"volatility\":0.20,\"riskFreeRate\":0.10,\"dividendYield\":0.0,\"type\":\"CALL\"}";
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/options/price"))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(400);
  }

  private JsonNode postJson(String path, String body) throws Exception {
    HttpResponse<String> response =
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
        .as("POST %s body=%s → %s", path, body, response.body())
        .isEqualTo(200);
    return MAPPER.readTree(response.body());
  }
}
