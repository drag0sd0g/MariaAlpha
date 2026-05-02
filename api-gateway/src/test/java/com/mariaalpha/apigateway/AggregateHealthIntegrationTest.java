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
class AggregateHealthIntegrationTest {

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
  static void dynamicProps(DynamicPropertyRegistry registry) {
    String base = downstream.url("/").toString().replaceAll("/$", "");
    registry.add("STRATEGY_ENGINE_MANAGEMENT_URL", () -> base);
    registry.add("EXECUTION_ENGINE_MANAGEMENT_URL", () -> base);
    registry.add("ORDER_MANAGER_MANAGEMENT_URL", () -> base);
    registry.add("POST_TRADE_MANAGEMENT_URL", () -> base);
    registry.add("ANALYTICS_SERVICE_MANAGEMENT_URL", () -> base);
    registry.add("MARIAALPHA_API_KEY", () -> "test-key");
  }

  @Test
  void healthIsUpWhenAllRequiredAreUp() {
    for (int i = 0; i < 10; i++) {
      downstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"UP\"}"));
    }

    webTestClient.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
  }
}
