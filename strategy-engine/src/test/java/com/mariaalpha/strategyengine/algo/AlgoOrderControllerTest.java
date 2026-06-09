package com.mariaalpha.strategyengine.algo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.strategyengine.model.Side;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AlgoOrderController.class)
class AlgoOrderControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AlgoOrderService service;
  @MockitoBean private AlgoOrderRegistry registry;

  @Test
  void submitReturns201WithAlgoOrderBody() throws Exception {
    var algo = sampleOrder();
    when(service.submit(any())).thenReturn(algo);

    mockMvc
        .perform(
            post("/api/algo/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"targetQuantity\":100,"
                        + "\"strategyName\":\"VWAP\",\"parameters\":{}}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.algoOrderId").value(algo.algoOrderId().toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void submitReturns400WhenStrategyUnknown() throws Exception {
    when(service.submit(any())).thenThrow(new IllegalArgumentException("Unknown strategy: BOGUS"));
    mockMvc
        .perform(
            post("/api/algo/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"targetQuantity\":100,"
                        + "\"strategyName\":\"BOGUS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string("Unknown strategy: BOGUS"));
  }

  @Test
  void submitRejectsMissingSymbol() throws Exception {
    mockMvc
        .perform(
            post("/api/algo/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"side\":\"BUY\",\"targetQuantity\":100,\"strategyName\":\"VWAP\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void submitRejectsZeroTargetQuantity() throws Exception {
    mockMvc
        .perform(
            post("/api/algo/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"targetQuantity\":0,"
                        + "\"strategyName\":\"VWAP\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getReturnsAlgoOrderWhenFound() throws Exception {
    var algo = sampleOrder();
    when(registry.find(algo.algoOrderId())).thenReturn(Optional.of(algo));

    mockMvc
        .perform(get("/api/algo/orders/" + algo.algoOrderId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.algoOrderId").value(algo.algoOrderId().toString()))
        .andExpect(jsonPath("$.symbol").value("AAPL"));
  }

  @Test
  void getReturns404WhenNotFound() throws Exception {
    when(registry.find(any())).thenReturn(Optional.empty());
    mockMvc.perform(get("/api/algo/orders/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void deleteReturnsCancelledAlgoOrder() throws Exception {
    var algo = sampleOrder().withStatus(AlgoOrder.Status.CANCELLED);
    when(service.cancel(any())).thenReturn(Optional.of(algo));

    mockMvc
        .perform(delete("/api/algo/orders/" + algo.algoOrderId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void deleteReturns404WhenIdUnknown() throws Exception {
    when(service.cancel(any())).thenReturn(Optional.empty());
    mockMvc
        .perform(delete("/api/algo/orders/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void listReturnsArrayOfAlgoOrders() throws Exception {
    var algo = sampleOrder();
    when(registry.all()).thenReturn(java.util.List.of(algo));
    mockMvc
        .perform(get("/api/algo/orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].symbol").value("AAPL"));
  }

  private static AlgoOrder sampleOrder() {
    var now = Instant.now();
    return new AlgoOrder(
        UUID.randomUUID(),
        "AAPL",
        Side.BUY,
        100,
        "VWAP",
        Map.of(),
        AlgoOrder.Status.ACTIVE,
        now,
        now);
  }
}
