package com.mariaalpha.posttrade.service;

import com.mariaalpha.posttrade.model.OrderDetails;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OrderManagerClient {

  private static final Logger LOG = LoggerFactory.getLogger(OrderManagerClient.class);

  private final RestClient restClient;

  public OrderManagerClient(RestClient orderManagerRestClient) {
    this.restClient = orderManagerRestClient;
  }

  public Optional<OrderDetails> fetchOrder(UUID orderId) {
    try {
      OrderDetails body =
          restClient.get().uri("/api/orders/{id}", orderId).retrieve().body(OrderDetails.class);
      return Optional.ofNullable(body);
    } catch (HttpStatusCodeException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        LOG.warn("Order {} not found in order-manager", orderId);
        return Optional.empty();
      }
      LOG.error("HTTP {} while fetching order {}: {}", e.getStatusCode(), orderId, e.getMessage());
      return Optional.empty();
    } catch (RestClientException e) {
      LOG.error("Transport failure while fetching order {}: {}", orderId, e.getMessage());
      return Optional.empty();
    }
  }
}
