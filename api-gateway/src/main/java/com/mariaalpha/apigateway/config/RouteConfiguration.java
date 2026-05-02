package com.mariaalpha.apigateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RouteConfiguration {

  @Bean
  public RouteLocator analyticsRoute(RouteLocatorBuilder builder, Environment env) {
    String analyticsUrl = env.getProperty("ANALYTICS_SERVICE_URL", "http://localhost:8095");
    return builder
        .routes()
        .route(
            "analytics",
            r ->
                r.path("/api/analytics/**")
                    .filters(
                        f ->
                            f.rewritePath(
                                "/api/analytics/(?<segment>.*)", "/v1/analytics/${segment}"))
                    .uri(analyticsUrl))
        .build();
  }
}
