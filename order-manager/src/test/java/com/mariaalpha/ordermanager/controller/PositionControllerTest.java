package com.mariaalpha.ordermanager.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.ordermanager.entity.PositionEntity;
import com.mariaalpha.ordermanager.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PositionController.class)
class PositionControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private PositionRepository positionRepository;

  @Test
  void listReturnsAllPositions() throws Exception {
    when(positionRepository.findAll()).thenReturn(List.of(position("AAPL", "100", "150")));

    mockMvc
        .perform(get("/api/positions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].symbol").value("AAPL"))
        .andExpect(jsonPath("$[0].netQuantity").value(100));
  }

  @Test
  void listIncludesFlatAndOpenPositions() throws Exception {
    when(positionRepository.findAll())
        .thenReturn(List.of(position("AAPL", "100", "150"), position("MSFT", "0", "0")));

    mockMvc
        .perform(get("/api/positions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void getBySymbolReturnsPosition() throws Exception {
    when(positionRepository.findById("AAPL"))
        .thenReturn(Optional.of(position("AAPL", "50", "200")));

    mockMvc
        .perform(get("/api/positions/AAPL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value("AAPL"))
        .andExpect(jsonPath("$.avgEntryPrice").value(200));
  }

  @Test
  void getBySymbolReturns404WhenMissing() throws Exception {
    when(positionRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/positions/UNKNOWN")).andExpect(status().isNotFound());
  }

  private PositionEntity position(String symbol, String qty, String avg) {
    var p = new PositionEntity(symbol);
    p.setNetQuantity(new BigDecimal(qty));
    p.setAvgEntryPrice(new BigDecimal(avg));
    p.setRealizedPnl(BigDecimal.ZERO);
    p.setUnrealizedPnl(BigDecimal.ZERO);
    p.setUpdatedAt(Instant.now());
    return p;
  }
}
