package com.mariaalpha.posttrade.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.posttrade.entity.TcaResultEntity;
import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.repository.TcaResultRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TcaController.class)
class TcaControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private TcaResultRepository repository;

  @Test
  void getOne_returnsTca() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(repository.findByOrderId(orderId)).thenReturn(Optional.of(entity(orderId)));
    mockMvc
        .perform(get("/api/tca/" + orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value(orderId.toString()))
        .andExpect(jsonPath("$.slippageBps").value(2.7778));
  }

  @Test
  void getOne_notFound_404() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(repository.findByOrderId(orderId)).thenReturn(Optional.empty());
    mockMvc.perform(get("/api/tca/" + orderId)).andExpect(status().isNotFound());
  }

  @Test
  void list_returnsPage() throws Exception {
    when(repository.search(eq("AAPL"), any(), any()))
        .thenReturn(List.of(entity(UUID.randomUUID())));
    mockMvc
        .perform(get("/api/tca").param("symbol", "AAPL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].symbol").value("AAPL"));
  }

  private static TcaResultEntity entity(UUID orderId) {
    TcaResultEntity e = new TcaResultEntity();
    e.setTcaId(UUID.randomUUID());
    e.setOrderId(orderId);
    e.setSymbol("AAPL");
    e.setStrategy("VWAP");
    e.setSide(Side.BUY);
    e.setQuantity(new BigDecimal("1000"));
    e.setSlippageBps(new BigDecimal("2.7778"));
    e.setImplShortfallBps(new BigDecimal("3.0556"));
    e.setVwapBenchmarkBps(new BigDecimal("1.1109"));
    e.setSpreadCostBps(new BigDecimal("1.1111"));
    e.setArrivalPrice(new BigDecimal("180.00"));
    e.setRealizedAvgPrice(new BigDecimal("180.05"));
    e.setVwapBenchmarkPrice(new BigDecimal("180.03"));
    e.setCommissionTotal(new BigDecimal("5.00"));
    e.setExecutionDurationMs(600000L);
    e.setComputedAt(Instant.now());
    return e;
  }
}
