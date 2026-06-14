package com.mariaalpha.apigateway.fix;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the inbound FIX 4.4 acceptor (roadmap 3.4.3).
 *
 * <p>Disabled by default — the acceptor opens a TCP listen socket, which we never want to do in
 * unit tests or CI. The message-translation and message-handling logic is exercised directly in
 * tests without starting the socket; flip {@code mariaalpha.fix.enabled=true} (e.g. in
 * docker-compose) to actually listen.
 *
 * @param enabled whether to start the FIX acceptor socket
 * @param host bind address for the acceptor
 * @param port listen port for the acceptor
 * @param senderCompId our SenderCompID (the venue side of the session)
 * @param targetCompId the expected client TargetCompID
 * @param heartbeatSeconds session heartbeat interval
 * @param executionEngineUrl base URL of execution-engine for order submission/cancel
 */
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
