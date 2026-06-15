package com.mariaalpha.posttrade.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.posttrade.model.FillForReconRecord;
import com.mariaalpha.posttrade.model.OrderDetails;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
  private static final TypeReference<List<FillForReconRecord>> FILL_LIST_TYPE =
      new TypeReference<>() {};

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public OrderManagerClient(RestClient orderManagerRestClient, ObjectMapper objectMapper) {
    this.restClient = orderManagerRestClient;
    this.objectMapper = objectMapper;
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

  public List<FillForReconRecord> fetchFillsForDate(LocalDate date) {
    try {
      String body =
          restClient
              .get()
              .uri(
                  uri ->
                      uri.path("/api/orders/fills/by-date")
                          .queryParam("date", date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                          .build())
              .retrieve()
              .body(String.class);
      if (body == null || body.isBlank()) {
        return List.of();
      }
      return objectMapper.readValue(body, FILL_LIST_TYPE);
    } catch (HttpStatusCodeException e) {
      LOG.error(
          "HTTP {} while fetching fills for date {}: {}", e.getStatusCode(), date, e.getMessage());
      throw new OrderManagerUnavailableException(
          "order-manager returned " + e.getStatusCode() + " for fills by date " + date, e);
    } catch (RestClientException e) {
      LOG.error("Transport failure while fetching fills for date {}: {}", date, e.getMessage());
      throw new OrderManagerUnavailableException(
          "Transport failure calling order-manager for fills by date " + date, e);
    } catch (Exception e) {
      throw new OrderManagerUnavailableException(
          "Unexpected failure parsing order-manager fills response", e);
    }
  }
}
