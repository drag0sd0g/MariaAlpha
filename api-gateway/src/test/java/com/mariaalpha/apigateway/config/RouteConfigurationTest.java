package com.mariaalpha.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

@SpringBootTest
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"mariaalpha.gateway.security.api-key=test-key"})
class RouteConfigurationTest {

  @Autowired RouteLocator routeLocator;

  @Test
  void allConfiguredRoutesArePresent() {
    StepVerifier.create(routeLocator.getRoutes())
        .recordWith(java.util.ArrayList::new)
        .thenConsumeWhile(r -> true)
        .consumeRecordedWith(
            routes ->
                assertThat(routes)
                    .extracting(r -> r.getId())
                    .contains(
                        "strategies",
                        "orders",
                        "execution",
                        "post-trade",
                        "market-data",
                        "analytics"))
        .verifyComplete();
  }
}
