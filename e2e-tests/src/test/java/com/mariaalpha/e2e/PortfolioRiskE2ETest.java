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

/**
 * End-to-end coverage for roadmap 4.6.1 through the API gateway.
 *
 * <p>Assertions are deliberately about <em>invariants</em> — weights summing to one, components
 * summing to VaR, the basket being accepted — rather than about specific quantities. Other e2e
 * classes trade on the same shared compose stack, so any assertion pinned to an exact position or
 * an exact VaR figure would be flaky by construction.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortfolioRiskE2ETest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private final String apiKey = SharedComposeStack.get().apiKey();
  private final String gatewayBaseUrl = SharedComposeStack.get().gatewayBaseUrl();
  private HttpClient httpClient;

  /** A book supplied explicitly so the test never depends on what other suites have traded. */
  private static final String EXPLICIT_BOOK =
      """
      [
        {"symbol": "NVDA", "quantity": 2000, "price": 500.0},
        {"symbol": "MSFT", "quantity": -2415, "price": 414.0},
        {"symbol": "AAPL", "quantity": 1500, "price": 200.0}
      ]
      """;

  @BeforeAll
  void startStack() {
    SharedComposeStack.get().start();
    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Test
  void portfolioStateIsServedThroughTheGateway() throws Exception {
    var body = getJson("/api/analytics/portfolio/state");
    assertThat(body.has("positions")).isTrue();
    assertThat(body.has("navUsd")).isTrue();
    assertThat(body.get("navSource").asText()).contains("config-cash");
  }

  @Test
  void covarianceModelIsSquareWithAUnitDiagonal() throws Exception {
    var body = getJson("/api/analytics/portfolio/covariance");
    var symbols = body.get("symbols");
    var correlation = body.get("correlation");
    assertThat(symbols.size()).isGreaterThan(0);
    assertThat(correlation.size()).isEqualTo(symbols.size());
    for (int i = 0; i < symbols.size(); i++) {
      assertThat(correlation.get(i).size()).isEqualTo(symbols.size());
      assertThat(correlation.get(i).get(i).asDouble()).isCloseTo(1.0, within(1e-6));
    }
    assertThat(body.get("diagnostics").get("source").asText())
        .isIn("prior", "sample+prior", "supplied");
  }

  @Test
  void riskParityEqualisesRiskContributions() throws Exception {
    var request =
        """
        {"objective": "RISK_PARITY", "constraints": {"max_weight": 1.0}, "positions": %s}
        """
            .formatted(EXPLICIT_BOOK);
    var body = postJson("/api/analytics/portfolio/optimize", request);

    double weightSum = 0.0;
    var weights = body.get("weights").fields();
    while (weights.hasNext()) {
      weightSum += weights.next().getValue().asDouble();
    }
    assertThat(weightSum).as("weights must be fully invested").isCloseTo(1.0, within(1e-6));

    double min = Double.MAX_VALUE;
    var contributions = body.get("riskContributions").fields();
    double max = -Double.MAX_VALUE;
    while (contributions.hasNext()) {
      double value = contributions.next().getValue().asDouble();
      min = Math.min(min, value);
      max = Math.max(max, value);
    }
    assertThat(max - min)
        .as("equal risk contribution is the defining property of risk parity")
        .isLessThan(1e-4);
  }

  @Test
  void covarianceVarIsBelowTheSumOfAbsolutes() throws Exception {
    var body =
        postJson(
            "/api/analytics/risk/var",
            """
            {"positions": %s, "confidence": 0.95, "horizon_days": 1.0}
            """
                .formatted(EXPLICIT_BOOK));

    double parametric = body.get("parametric").get("varUsd").asDouble();
    double sumOfAbsolutes = body.get("sumOfAbsolutesVarUsd").asDouble();
    double ratio = body.get("diversificationRatio").asDouble();

    assertThat(parametric).isGreaterThan(0.0);
    assertThat(sumOfAbsolutes)
        .as("the pre-4.6.1 aggregation is an upper bound on the covariance one")
        .isGreaterThanOrEqualTo(parametric);
    assertThat(ratio).isGreaterThanOrEqualTo(1.0);
    assertThat(body.get("monteCarlo").get("varUsd").asDouble()).isGreaterThan(0.0);
  }

  @Test
  void componentVarSumsToPortfolioVar() throws Exception {
    var body = getJson("/api/analytics/risk/components");
    var rows = body.get("rows");
    if (rows.isEmpty()) {
      // No positions on the shared stack yet — the invariant is vacuous but the shape must hold.
      assertThat(body.has("varUsd")).isTrue();
      return;
    }
    double total = 0.0;
    for (var row : rows) {
      total += row.get("componentVarUsd").asDouble();
    }
    assertThat(total)
        .as("Euler allocation is exact, so components must reconstruct portfolio VaR")
        .isCloseTo(body.get("varUsd").asDouble(), within(Math.max(1.0, total * 0.005)));
  }

  @Test
  void blackLittermanWithoutViewsReturnsTheEquilibrium() throws Exception {
    var body = postJson("/api/analytics/portfolio/black-litterman", "{\"views\": []}");
    var equilibrium = body.get("equilibriumReturns");
    var posterior = body.get("posteriorReturns");
    assertThat(posterior).isEqualTo(equilibrium);
  }

  @Test
  void stressReturnsEveryConfiguredScenario() throws Exception {
    var body = getJson("/api/analytics/risk/stress");
    var names = body.get("scenarios").findValuesAsText("name");
    assertThat(names)
        .contains("MARKET_DOWN_10", "TECH_SELLOFF", "COVID_20200316", "AI_UNWIND");
  }

  @Test
  void factorsExposeMarketBetaAndPca() throws Exception {
    var body = getJson("/api/analytics/portfolio/factors");
    assertThat(body.get("exposures").has("MARKET")).isTrue();
    double explained = 0.0;
    for (var value : body.get("pca").get("varianceExplained")) {
      explained += value.asDouble();
    }
    assertThat(explained).isCloseTo(1.0, within(1e-4));
  }

  @Test
  void riskReportRendersAsHtml() throws Exception {
    var response = send("/api/analytics/risk/report", null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Component VaR");
    assertThat(response.body()).contains("Diversification credit");
  }

  @Test
  void rebalanceSubmitIsDisabledByDefault() throws Exception {
    var response = send("/api/analytics/portfolio/rebalance/submit", "{}");
    assertThat(response.statusCode())
        .as("sending live orders from analytics must be opt-in")
        .isEqualTo(503);
  }

  /**
   * The integration proof: the rebalancer's output is accepted verbatim by the basket-trading
   * service, which is the whole point of building the construction layer on top of execution.
   */
  @Test
  void rebalancePlanIsAcceptedByTheBasketService() throws Exception {
    var plan =
        postJson(
            "/api/analytics/portfolio/rebalance",
            """
            {"objective": "RISK_PARITY", "constraints": {"max_weight": 0.5},
             "positions": %s, "min_trade_notional": 1000}
            """
                .formatted(EXPLICIT_BOOK));

    var basketRequest = plan.get("basketOrderRequest");
    assertThat(basketRequest.get("name").asText()).isNotBlank();
    var legs = basketRequest.get("legs");
    assertThat(legs.size()).as("the plan must produce at least one tradeable leg").isPositive();
    for (var leg : legs) {
      assertThat(leg.get("quantity").asInt())
          .as("BasketLegRequest.quantity is @Min(1)")
          .isGreaterThanOrEqualTo(1);
      assertThat(leg.get("side").asText()).isIn("BUY", "SELL");
      assertThat(leg.get("orderType").asText()).isEqualTo("MARKET");
    }

    var submitted = send("/api/execution/baskets", MAPPER.writeValueAsString(basketRequest));
    assertThat(submitted.statusCode())
        .as("execution-engine must accept the rebalancer's payload unchanged")
        .isEqualTo(202);

    var basketId = MAPPER.readTree(submitted.body()).get("basketId").asText();
    assertThat(basketId).isNotBlank();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(1, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var view = getJson("/api/execution/baskets/" + basketId);
              assertThat(view.get("legs").size()).isEqualTo(legs.size());
            });
  }

  // ------------------------------------------------------------------- helpers

  private static org.assertj.core.data.Offset<Double> within(double tolerance) {
    return org.assertj.core.data.Offset.offset(tolerance);
  }

  private JsonNode getJson(String path) throws Exception {
    var response = send(path, null);
    assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
    return MAPPER.readTree(response.body());
  }

  private JsonNode postJson(String path, String body) throws Exception {
    var response = send(path, body);
    assertThat(response.statusCode()).as("POST %s -> %s", path, response.body()).isEqualTo(200);
    return MAPPER.readTree(response.body());
  }

  private HttpResponse<String> send(String path, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder()
            .uri(URI.create(gatewayBaseUrl + path))
            .header("X-API-Key", apiKey)
            .timeout(Duration.ofSeconds(30));
    if (body == null) {
      builder.GET();
    } else {
      builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
