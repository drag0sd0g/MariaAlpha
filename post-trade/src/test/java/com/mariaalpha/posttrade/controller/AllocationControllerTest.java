package com.mariaalpha.posttrade.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.posttrade.allocation.AllocationMethod;
import com.mariaalpha.posttrade.allocation.AllocationService;
import com.mariaalpha.posttrade.allocation.SubAccountConfig.SubAccount;
import com.mariaalpha.posttrade.allocation.SubAccountRegistry;
import com.mariaalpha.posttrade.controller.dto.AllocationRequestDto;
import com.mariaalpha.posttrade.entity.AllocationEntity;
import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.repository.AllocationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AllocationController.class)
class AllocationControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper json;
  @MockBean private AllocationService service;
  @MockBean private AllocationRepository repository;
  @MockBean private SubAccountRegistry registry;

  @Test
  void subAccountsListReturnsRoster() throws Exception {
    when(registry.defaultMethod()).thenReturn(AllocationMethod.PRO_RATA);
    when(registry.accounts())
        .thenReturn(List.of(new SubAccount("HOUSE", 50.0), new SubAccount("HF_A", 50.0)));
    mockMvc
        .perform(get("/api/allocations/sub-accounts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultMethod").value("PRO_RATA"))
        .andExpect(jsonPath("$.subAccounts[0].name").value("HOUSE"))
        .andExpect(jsonPath("$.subAccounts[1].name").value("HF_A"));
  }

  @Test
  void runReturns201AndPersistedAllocations() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(service.allocate(any()))
        .thenReturn(List.of(entity(orderId, "HOUSE", "500"), entity(orderId, "HF_A", "500")));
    var request =
        new AllocationRequestDto(
            orderId, "AAPL", Side.BUY, new BigDecimal("1000"), new BigDecimal("178.42"), null);
    mockMvc
        .perform(
            post("/api/allocations/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].subAccount").value("HOUSE"))
        .andExpect(jsonPath("$[1].subAccount").value("HF_A"));
  }

  @Test
  void runReturns400OnInvalidQuantity() throws Exception {
    when(service.allocate(any()))
        .thenThrow(new IllegalArgumentException("parentFilledQuantity must be > 0"));
    var request =
        new AllocationRequestDto(
            UUID.randomUUID(), "AAPL", Side.BUY, BigDecimal.ZERO, new BigDecimal("178.42"), null);
    mockMvc
        .perform(
            post("/api/allocations/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void runReturns503WhenAllocationUnconfigured() throws Exception {
    when(service.allocate(any()))
        .thenThrow(new IllegalStateException("No sub-accounts configured"));
    var request =
        new AllocationRequestDto(
            UUID.randomUUID(),
            "AAPL",
            Side.BUY,
            new BigDecimal("100"),
            new BigDecimal("178.42"),
            null);
    mockMvc
        .perform(
            post("/api/allocations/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void byOrderListsAllocations() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(repository.findByOrderIdOrderBySubAccount(orderId))
        .thenReturn(List.of(entity(orderId, "HOUSE", "500")));
    mockMvc
        .perform(get("/api/allocations/order/" + orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].subAccount").value("HOUSE"));
  }

  @Test
  void bySubAccountListsAllocations() throws Exception {
    when(repository.findBySubAccountOrderByAllocatedAtDesc("HOUSE"))
        .thenReturn(List.of(entity(UUID.randomUUID(), "HOUSE", "500")));
    mockMvc
        .perform(get("/api/allocations/sub-account/HOUSE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].subAccount").value("HOUSE"));
  }

  private static AllocationEntity entity(UUID orderId, String subAccount, String quantity) {
    var e = new AllocationEntity();
    e.setAllocationId(UUID.randomUUID());
    e.setOrderId(orderId);
    e.setSubAccount(subAccount);
    e.setSymbol("AAPL");
    e.setSide(Side.BUY);
    e.setAllocatedQuantity(new BigDecimal(quantity));
    e.setAllocatedAvgPrice(new BigDecimal("178.42"));
    e.setAllocationMethod(AllocationMethod.PRO_RATA);
    e.setParentFilledQuantity(new BigDecimal("1000"));
    e.setParentAvgPrice(new BigDecimal("178.42"));
    e.setAllocatedAt(Instant.now());
    return e;
  }
}
