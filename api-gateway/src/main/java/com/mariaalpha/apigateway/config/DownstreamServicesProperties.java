package com.mariaalpha.apigateway.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mariaalpha.gateway")
public record DownstreamServicesProperties(Map<String, Downstream> downstreams) {
  public record Downstream(String url, String managementUrl, boolean required) {
    public String healthUrl() {
      var base = managementUrl == null || managementUrl.isBlank() ? url : managementUrl;
      return base.endsWith("/") ? base + "actuator/health" : base + "/actuator/health";
    }
  }
}
