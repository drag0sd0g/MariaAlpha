package com.mariaalpha.executionengine.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.config.AlpacaConfig;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.OrderAck;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("alpaca")
public class AlpacaExchangeAdapter implements ExchangeAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(AlpacaExchangeAdapter.class);

  private final AlpacaConfig config;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final OkHttpClient wsClient; // lightweight websocket client
  private final AtomicBoolean connected = new AtomicBoolean(false);
  private volatile Consumer<ExecutionReport> reportCallback;
  private volatile okhttp3.WebSocket webSocket;

  public AlpacaExchangeAdapter(AlpacaConfig config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.wsClient = new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(30)).build();
  }

  @Override
  public OrderAck submitOrder(ExecutionInstruction instruction) {
    var order = instruction.order();
    var body =
        Map.of(
            "symbol", order.getSymbol(),
            "qty", String.valueOf(order.getQuantity()),
            "side", order.getSide().name().toLowerCase(),
            "type", order.getOrderType().getName(),
            "time_in_force", instruction.timeInForce(),
            "limit_price",
                order.getLimitPrice() != null ? order.getLimitPrice().toPlainString() : null,
            "stop_price",
                order.getStopPrice() != null ? order.getStopPrice().toPlainString() : null,
            "client_order_id", order.getOrderId());

    try {
      var json = objectMapper.writeValueAsString(body);
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.baseUrl() + "/v2/orders"))
              .header("APCA-API-KEY-ID", config.apiKey())
              .header("APCA-API-SECRET-KEY", config.apiSecret())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .timeout(Duration.ofSeconds(5))
              .build();

      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200 || response.statusCode() == 201) {
        var node = objectMapper.readTree(response.body());
        var exchangeId = node.get("id").asText();
        return new OrderAck(order.getOrderId(), exchangeId, true, "");
      } else {
        var errMsg = response.body();
        LOG.warn("Alpaca order rejected: {}", errMsg);
        return new OrderAck(order.getOrderId(), "", false, errMsg);
      }

    } catch (Exception ex) {
      LOG.error("Alpaca order submission failed: {}", ex.getMessage(), ex);
      return new OrderAck(order.getOrderId(), "", false, ex.getMessage());
    }
  }

  @Override
  public OrderAck cancelOrder(String exchangeOrderId) {
    try {
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.baseUrl() + "/v2/orders/" + exchangeOrderId))
              .header("APCA-API-KEY-ID", config.apiKey())
              .header("APCA-API-SECRET-KEY", config.apiSecret())
              .DELETE()
              .timeout(Duration.ofSeconds(5))
              .build();

      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      boolean success = response.statusCode() == 200 || response.statusCode() == 204;
      return new OrderAck("", exchangeOrderId, success, success ? "cancelled" : response.body());
    } catch (Exception ex) {
      LOG.error("Alpaca order cancellation failed: {}", ex.getMessage(), ex);
      return new OrderAck("", exchangeOrderId, false, ex.getMessage());
    }
  }

  @Override
  public void onExecutionReport(Consumer<ExecutionReport> callback) {
    this.reportCallback = callback;
  }

  @Override
  public void start() {
    // Connect to Alpaca trade updates
    var request = new Request.Builder().url(config.websocketUrl()).build();
    this.webSocket =
        wsClient.newWebSocket(
            request, new AlpacaWebSocketListener(config, objectMapper, reportCallback, connected));
  }

  @PreDestroy
  @Override
  public void shutdown() {
    if (webSocket != null) {
      webSocket.close(1000, "shutdown");
    }
    wsClient.dispatcher().executorService().shutdown();
  }

  @Override
  public boolean isHealthy() {
    return connected.get();
  }
}
