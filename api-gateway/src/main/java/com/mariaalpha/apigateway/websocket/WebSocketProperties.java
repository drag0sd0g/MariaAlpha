package com.mariaalpha.apigateway.websocket;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mariaalpha.gateway.websocket")
public record WebSocketProperties(Map<String, Endpoint> endpoints, int backpressureBufferSize) {

  public WebSocketProperties {
    if (backpressureBufferSize <= 0) {
      backpressureBufferSize = 1024;
    }
  }

  public record Endpoint(String path, String topic, String filterKey) {}
}
