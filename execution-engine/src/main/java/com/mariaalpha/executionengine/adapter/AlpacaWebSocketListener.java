package com.mariaalpha.executionengine.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.config.AlpacaConfig;
import com.mariaalpha.executionengine.model.ExecutionReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlpacaWebSocketListener extends WebSocketListener {

  private static final Logger LOG = LoggerFactory.getLogger(AlpacaWebSocketListener.class);

  private final AlpacaConfig config;
  private final ObjectMapper objectMapper;
  private final Consumer<ExecutionReport> reportCallback;
  private final AtomicBoolean connected;

  public AlpacaWebSocketListener(
      AlpacaConfig config,
      ObjectMapper objectMapper,
      Consumer<ExecutionReport> reportCallback,
      AtomicBoolean connected) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.reportCallback = reportCallback;
    this.connected = connected;
  }

  @Override
  public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
    connected.set(true);
    var authMsg =
        String.format(
            "{\"action\":\"auth\",\"key\":\"%s\",\"secret\":\"%s\"}",
            config.apiKey(), config.apiSecret());
    webSocket.send(authMsg);
    webSocket.send("{\"action\":\"listen\",\"data\":{\"streams\":[\"trade_updates\"]}}");
    LOG.info("Alpaca WebSocket connected - subscribed to trade_updates stream");
  }

  @Override
  public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
    try {
      var root = objectMapper.readTree(text);
      var stream = root.path("stream").asText();
      if ("trade_updates".equals(stream)) {
        handleTradeUpdate(root.path("data"));
      }
    } catch (Exception e) {
      LOG.warn("Failed to parse WebSocket message: {}", e.getMessage(), e);
    }
  }

  private void handleTradeUpdate(JsonNode tradeUpdate) {
    var event = tradeUpdate.get("event").asText();
    if ("fill".equals(event) || "partial_fill".equals(event)) {
      var executionReport =
          new ExecutionReport(
              tradeUpdate.get("order").get("id").asText(),
              new BigDecimal(tradeUpdate.get("price").asText()),
              tradeUpdate.get("qty").asInt(),
              tradeUpdate.get("order").get("qty").asInt()
                  - tradeUpdate.get("order").get("filled_qty").asInt(),
              "ALPACA",
              Instant.parse(tradeUpdate.get("timestamp").asText()));
      if (reportCallback != null) {
        reportCallback.accept(executionReport);
      }
    }
  }

  @Override
  public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
    connected.set(false);
    LOG.warn("Alpaca WebSocket closed: {} {}", code, reason);
  }

  @Override
  public void onFailure(
      @NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
    connected.set(false);
    LOG.warn("Alpaca WebSocket failure: {}", t.getMessage(), t);
    // TODO reconnect
  }
}
