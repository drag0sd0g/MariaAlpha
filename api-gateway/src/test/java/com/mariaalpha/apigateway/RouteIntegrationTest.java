package com.mariaalpha.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "management.server.port=")
class RouteIntegrationTest {

  static MockWebServer strategyEngine;
  static MockWebServer orderManager;
  static MockWebServer executionEngine;
  static MockWebServer postTrade;
  static MockWebServer analyticsService;
  static MockWebServer marketDataGateway;

  @Autowired WebTestClient webTestClient;

  @BeforeAll
  static void start() throws IOException {
    strategyEngine = startServer();
    orderManager = startServer();
    executionEngine = startServer();
    postTrade = startServer();
    analyticsService = startServer();
    marketDataGateway = startServer();
  }

  @AfterAll
  static void stop() throws IOException {
    strategyEngine.shutdown();
    orderManager.shutdown();
    executionEngine.shutdown();
    postTrade.shutdown();
    analyticsService.shutdown();
    marketDataGateway.shutdown();
  }

  @DynamicPropertySource
  static void dynamicProps(DynamicPropertyRegistry registry) {
    registry.add("STRATEGY_ENGINE_URL", () -> base(strategyEngine));
    registry.add("ORDER_MANAGER_URL", () -> base(orderManager));
    registry.add("EXECUTION_ENGINE_URL", () -> base(executionEngine));
    registry.add("POST_TRADE_URL", () -> base(postTrade));
    registry.add("ANALYTICS_SERVICE_URL", () -> base(analyticsService));
    registry.add("MARKET_DATA_GATEWAY_URL", () -> base(marketDataGateway));
    registry.add("MARIAALPHA_API_KEY", () -> "integration-test-key");
  }

  private static MockWebServer startServer() throws IOException {
    var server = new MockWebServer();
    server.start();
    return server;
  }

  private static String base(MockWebServer server) {
    return server.url("/").toString().replaceAll("/$", "");
  }

  @Test
  void strategiesRouteForwardsToStrategyEngine() throws InterruptedException {
    strategyEngine.enqueue(new MockResponse().setBody("[\"vwap\",\"twap\"]").setResponseCode(200));

    webTestClient
        .get()
        .uri("/api/strategies")
        .header("X-API-Key", "integration-test-key")
        .exchange()
        .expectStatus()
        .isOk();

    RecordedRequest req = strategyEngine.takeRequest();
    assertThat(req.getPath()).isEqualTo("/api/strategies");
  }

  @Test
  void ordersRouteForwardsToOrderManager() throws InterruptedException {
    orderManager.enqueue(new MockResponse().setBody("[]").setResponseCode(200));

    webTestClient
        .get()
        .uri("/api/orders?symbol=AAPL")
        .header("X-API-Key", "integration-test-key")
        .exchange()
        .expectStatus()
        .isOk();

    RecordedRequest req = orderManager.takeRequest();
    assertThat(req.getPath()).isEqualTo("/api/orders?symbol=AAPL");
  }

  @Test
  void analyticsRouteRewritesPathPrefix() throws InterruptedException {
    analyticsService.enqueue(new MockResponse().setBody("{\"value\":1}").setResponseCode(200));

    webTestClient
        .get()
        .uri("/api/analytics/pnl/daily")
        .header("X-API-Key", "integration-test-key")
        .exchange()
        .expectStatus()
        .isOk();

    RecordedRequest req = analyticsService.takeRequest();
    assertThat(req.getPath()).isEqualTo("/v1/analytics/pnl/daily");
  }

  @Test
  void rejectsRequestsWithoutApiKey() {
    webTestClient.get().uri("/api/orders").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void allowsActuatorWithoutApiKey() {
    webTestClient.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
  }
}
