package com.mariaalpha.ordermanager.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.ordermanager.entity.OrderEntity;
import com.mariaalpha.ordermanager.model.OrderStatus;
import com.mariaalpha.ordermanager.model.OrderType;
import com.mariaalpha.ordermanager.model.Side;
import com.mariaalpha.ordermanager.repository.FillRepository;
import com.mariaalpha.ordermanager.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private OrderRepository orderRepository;
  @MockitoBean private FillRepository fillRepository;

  @Test
  void listReturnsOrders() throws Exception {
    when(orderRepository.search(any(), any(), any(), any(), any(), any(Pageable.class)))
        .thenReturn(List.of(sampleOrder("c1", "AAPL"), sampleOrder("c2", "MSFT")));

    mockMvc
        .perform(get("/api/orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].symbol").value("AAPL"))
        .andExpect(jsonPath("$[1].symbol").value("MSFT"));
  }

  @Test
  void listPassesSymbolFilter() throws Exception {
    when(orderRepository.search(eq("AAPL"), any(), any(), any(), any(), any(Pageable.class)))
        .thenReturn(List.of(sampleOrder("c1", "AAPL")));

    mockMvc
        .perform(get("/api/orders?symbol=AAPL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void listPassesStatusFilter() throws Exception {
    when(orderRepository.search(
            any(), eq(OrderStatus.FILLED), any(), any(), any(), any(Pageable.class)))
        .thenReturn(List.of(sampleOrder("c1", "AAPL")));

    mockMvc
        .perform(get("/api/orders?status=FILLED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void listRejectsFromAfterTo() throws Exception {
    mockMvc
        .perform(get("/api/orders?from=2026-04-20T00:00:00Z&to=2026-04-10T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getReturnsOrderWithFills() throws Exception {
    var order = sampleOrder("c1", "AAPL");
    when(orderRepository.findById(order.getOrderId())).thenReturn(Optional.of(order));
    when(fillRepository.findByOrder_OrderIdOrderByFilledAtAsc(order.getOrderId()))
        .thenReturn(List.of());

    mockMvc
        .perform(get("/api/orders/" + order.getOrderId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value("AAPL"));
  }

  @Test
  void getReturns404WhenMissing() throws Exception {
    var id = UUID.randomUUID();
    when(orderRepository.findById(id)).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/orders/" + id)).andExpect(status().isNotFound());
  }

  @Test
  void listClampsLimitToMax() throws Exception {
    when(orderRepository.search(any(), any(), any(), any(), any(), any(Pageable.class)))
        .thenReturn(List.of());

    mockMvc.perform(get("/api/orders?limit=10000")).andExpect(status().isOk());
  }

  private OrderEntity sampleOrder(String clientId, String symbol) {
    var order = new OrderEntity();
    order.setOrderId(UUID.randomUUID());
    order.setClientOrderId(clientId);
    order.setSymbol(symbol);
    order.setSide(Side.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setQuantity(BigDecimal.valueOf(100));
    order.setStatus(OrderStatus.FILLED);
    order.setFilledQuantity(BigDecimal.valueOf(100));
    order.setAvgFillPrice(BigDecimal.valueOf(150));
    order.setCreatedAt(Instant.now());
    order.setUpdatedAt(Instant.now());
    return order;
  }
}
