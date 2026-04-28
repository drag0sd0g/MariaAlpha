package com.mariaalpha.posttrade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mariaalpha.posttrade.model.OrderStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OrderManagerClientTest {

  private MockRestServiceServer server;
  private OrderManagerClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
    server = MockRestServiceServer.bindTo(builder).build();
    client = new OrderManagerClient(builder.build());
  }

  @Test
  void returnsBodyOnSuccess() {
    UUID orderId = UUID.randomUUID();
    String json =
        "{\"orderId\":\""
            + orderId
            + "\",\"clientOrderId\":\"c\",\"symbol\":\"AAPL\","
            + "\"side\":\"BUY\",\"orderType\":\"MARKET\",\"quantity\":100,\"status\":\"FILLED\","
            + "\"strategy\":\"VWAP\",\"filledQuantity\":100,\"avgFillPrice\":180.05,"
            + "\"createdAt\":\"2026-04-20T09:30:00Z\",\"updatedAt\":\"2026-04-20T09:40:00Z\","
            + "\"fills\":[]}";
    server
        .expect(requestTo("http://localhost/api/orders/" + orderId))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

    var result = client.fetchOrder(orderId);
    assertThat(result).isPresent();
    assertThat(result.get().status()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void returnsEmptyOn404() {
    UUID orderId = UUID.randomUUID();
    server
        .expect(requestTo("http://localhost/api/orders/" + orderId))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));
    assertThat(client.fetchOrder(orderId)).isEmpty();
  }

  @Test
  void returnsEmptyOn5xx() {
    UUID orderId = UUID.randomUUID();
    server
        .expect(requestTo("http://localhost/api/orders/" + orderId))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    assertThat(client.fetchOrder(orderId)).isEmpty();
  }

  @Test
  void ignoresUnknownJsonFields() {
    UUID orderId = UUID.randomUUID();
    String json =
        "{\"orderId\":\""
            + orderId
            + "\",\"symbol\":\"AAPL\",\"side\":\"BUY\","
            + "\"orderType\":\"LIMIT\",\"quantity\":100,\"status\":\"FILLED\","
            + "\"strategy\":\"VWAP\",\"filledQuantity\":100,\"avgFillPrice\":180.05,"
            + "\"createdAt\":\"2026-04-20T09:30:00Z\",\"updatedAt\":\"2026-04-20T09:40:00Z\","
            + "\"fills\":[],\"somethingNew\":42,\"nested\":{\"foo\":\"bar\"}}";
    server
        .expect(requestTo("http://localhost/api/orders/" + orderId))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    assertThat(client.fetchOrder(orderId)).isPresent();
  }
}
