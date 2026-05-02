package com.mariaalpha.apigateway.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mariaalpha.gateway.security")
public record SecurityProperties(
    String apiKey, String headerName, String queryParamName, List<String> excludedPaths) {

  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }
}
