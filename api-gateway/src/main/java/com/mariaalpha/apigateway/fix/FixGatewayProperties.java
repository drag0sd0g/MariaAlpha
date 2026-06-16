package com.mariaalpha.apigateway.fix;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mariaalpha.fix")
public record FixGatewayProperties(
    boolean enabled,
    String host,
    int port,
    String senderCompId,
    String targetCompId,
    int heartbeatSeconds,
    String executionEngineUrl) {

  public FixGatewayProperties {
    host = host == null || host.isBlank() ? "0.0.0.0" : host;
    port = port == 0 ? 9878 : port;
    senderCompId = senderCompId == null || senderCompId.isBlank() ? "MARIAALPHA" : senderCompId;
    targetCompId = targetCompId == null || targetCompId.isBlank() ? "CLIENT" : targetCompId;
    heartbeatSeconds = heartbeatSeconds == 0 ? 30 : heartbeatSeconds;
    executionEngineUrl =
        executionEngineUrl == null || executionEngineUrl.isBlank()
            ? "http://localhost:8084"
            : executionEngineUrl;
  }
}
