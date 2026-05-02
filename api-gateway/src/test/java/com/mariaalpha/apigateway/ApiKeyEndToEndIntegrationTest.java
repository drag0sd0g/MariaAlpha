package com.mariaalpha.apigateway;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "management.server.port=")
class ApiKeyEndToEndIntegrationTest {

  static MockWebServer downstream;

  @Autowired WebTestClient webTestClient;

  @BeforeAll
  static void start() throws IOException {
    downstream = new MockWebServer();
    downstream.start();
  }

  @AfterAll
  static void stop() throws IOException {
    downstream.shutdown();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    String base = downstream.url("/").toString().replaceAll("/$", "");
    registry.add("STRATEGY_ENGINE_URL", () -> base);
    registry.add("ORDER_MANAGER_URL", () -> base);
    registry.add("EXECUTION_ENGINE_URL", () -> base);
    registry.add("POST_TRADE_URL", () -> base);
    registry.add("ANALYTICS_SERVICE_URL", () -> base);
    registry.add("MARKET_DATA_GATEWAY_URL", () -> base);
    registry.add("MARIAALPHA_API_KEY", () -> "e2e-key");
  }

  @Test
  void rest401WithoutKey() {
    webTestClient.get().uri("/api/orders").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void rest200WithKey() {
    downstream.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));
    webTestClient
        .get()
        .uri("/api/orders")
        .header("X-API-Key", "e2e-key")
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void rest200WithKeyInQueryParam() {
    downstream.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));
    webTestClient.get().uri("/api/orders?apiKey=e2e-key").exchange().expectStatus().isOk();
  }

  @Test
  void actuatorAccessibleWithoutKey() {
    webTestClient.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
  }
}
