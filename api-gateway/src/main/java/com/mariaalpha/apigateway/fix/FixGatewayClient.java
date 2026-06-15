package com.mariaalpha.apigateway.fix;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class FixGatewayClient {

  private static final Logger LOG = LoggerFactory.getLogger(FixGatewayClient.class);

  private final WebClient webClient;

  public FixGatewayClient(WebClient.Builder builder, FixGatewayProperties properties) {
    this.webClient = builder.baseUrl(properties.executionEngineUrl()).build();
  }

  public FixDownstreamResult submitOrder(FixOrderSubmission order) {
    var body = new HashMap<String, Object>();
    body.put("symbol", order.symbol());
    body.put("side", order.side());
    body.put("orderType", order.orderType());
    body.put("quantity", order.quantity());
    body.put("clientOrderId", order.clOrdId());
    if (order.limitPrice() != null) {
      body.put("limitPrice", order.limitPrice());
    }
    if (order.stopPrice() != null) {
      body.put("stopPrice", order.stopPrice());
    }
    if (order.timeInForce() != null) {
      body.put("tif", order.timeInForce());
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> response =
          webClient
              .post()
              .uri("/api/execution/orders")
              .bodyValue(body)
              .retrieve()
              .bodyToMono(Map.class)
              .block();
      if (response == null) {
        return FixDownstreamResult.rejected("empty response from execution-engine");
      }
      var orderId = String.valueOf(response.get("orderId"));
      var status = String.valueOf(response.get("status"));
      if ("REJECTED".equals(status)) {
        return FixDownstreamResult.rejected("rejected by execution-engine");
      }
      return FixDownstreamResult.accepted(orderId);
    } catch (WebClientResponseException e) {
      LOG.warn("execution-engine rejected FIX order {}: {}", order.clOrdId(), e.getStatusCode());
      return FixDownstreamResult.rejected("execution-engine HTTP " + e.getStatusCode().value());
    } catch (RuntimeException e) {
      LOG.error("Failed to forward FIX order {} to execution-engine", order.clOrdId(), e);
      return FixDownstreamResult.rejected("execution-engine unavailable");
    }
  }

  public FixDownstreamResult cancelOrder(String downstreamOrderId) {
    try {
      webClient
          .delete()
          .uri("/api/execution/orders/{id}", downstreamOrderId)
          .retrieve()
          .toBodilessEntity()
          .block();
      return FixDownstreamResult.accepted(downstreamOrderId);
    } catch (WebClientResponseException.NotFound e) {
      return FixDownstreamResult.rejected("unknown or already-terminal order");
    } catch (RuntimeException e) {
      LOG.error("Failed to cancel order {} at execution-engine", downstreamOrderId, e);
      return FixDownstreamResult.rejected("execution-engine unavailable");
    }
  }
}
