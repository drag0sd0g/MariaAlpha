package com.mariaalpha.executionengine.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.config.AlpacaConfig;
import com.mariaalpha.executionengine.model.ExecutionReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlpacaWebSocketListener extends WebSocketListener {

  private static final Logger LOG = LoggerFactory.getLogger(AlpacaWebSocketListener.class);

  private final AlpacaConfig config;
  private final ObjectMapper objectMapper;
  private final Supplier<Consumer<ExecutionReport>> reportCallbackSupplier;
  private final AtomicBoolean connected;
  private final Runnable reconnectTrigger;
  private final Runnable onOpenSuccess;

  public AlpacaWebSocketListener(
      AlpacaConfig config,
      ObjectMapper objectMapper,
      Supplier<Consumer<ExecutionReport>> reportCallbackSupplier,
      AtomicBoolean connected) {
    this(config, objectMapper, reportCallbackSupplier, connected, null, null);
  }

  public AlpacaWebSocketListener(
      AlpacaConfig config,
      ObjectMapper objectMapper,
      Supplier<Consumer<ExecutionReport>> reportCallbackSupplier,
      AtomicBoolean connected,
      Runnable reconnectTrigger,
      Runnable onOpenSuccess) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.reportCallbackSupplier = reportCallbackSupplier;
    this.connected = connected;
    this.reconnectTrigger = reconnectTrigger;
    this.onOpenSuccess = onOpenSuccess;
  }

  @Override
  public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
    connected.set(true);
    var authMsg =
        String.format(
            "{\"action\":\"authenticate\",\"data\":{\"key_id\":\"%s\",\"secret_key\":\"%s\"}}",
            config.apiKey(), config.apiSecret());
    webSocket.send(authMsg);
    webSocket.send("{\"action\":\"listen\",\"data\":{\"streams\":[\"trade_updates\"]}}");
    LOG.info("Alpaca WebSocket connected - sent auth + listen for trade_updates");
    if (onOpenSuccess != null) {
      onOpenSuccess.run();
    }
  }

  @Override
  public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
    LOG.info("Alpaca WebSocket text frame: {}", text);
    parseAndDispatch(text);
  }

  @Override
  public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
    try {
      var node = objectMapper.readTree(bytes.toByteArray());
      LOG.info("Alpaca WebSocket binary frame (msgpack/json): {}", node);
      dispatch(node);
    } catch (Exception e) {
      LOG.warn(
          "Alpaca WebSocket binary frame parse failed (len={}, hex={}): {}",
          bytes.size(),
          bytes.hex().substring(0, Math.min(bytes.hex().length(), 80)),
          e.getMessage());
    }
  }

  private void parseAndDispatch(String text) {
    try {
      dispatch(objectMapper.readTree(text));
    } catch (Exception e) {
      LOG.warn("Failed to parse WebSocket text message: {}", e.getMessage(), e);
    }
  }

  private void dispatch(JsonNode root) {
    var stream = root.path("stream").asText();
    if ("trade_updates".equals(stream)) {
      handleTradeUpdate(root.path("data"));
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
      Consumer<ExecutionReport> cb = reportCallbackSupplier.get();
      if (cb != null) {
        cb.accept(executionReport);
      } else {
        LOG.warn("Dropping fill — reportCallback not yet wired");
      }
    }
  }

  @Override
  public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
    connected.set(false);
    LOG.warn("Alpaca WebSocket closed: {} {}", code, reason);
    if (code != 1000 && reconnectTrigger != null) {
      reconnectTrigger.run();
    }
  }

  @Override
  public void onFailure(
      @NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
    connected.set(false);
    LOG.warn("Alpaca WebSocket failure: {}", t.getMessage(), t);
    if (reconnectTrigger != null) {
      reconnectTrigger.run();
    }
  }
}
