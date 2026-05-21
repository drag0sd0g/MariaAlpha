package com.mariaalpha.executionengine.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mariaalpha.executionengine.iceberg.IcebergProgress;
import com.mariaalpha.executionengine.iceberg.ParentChildOrderRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IcebergController.class)
class IcebergControllerTest {

  @TestConfiguration
  static class Config {
    @Bean
    ParentChildOrderRegistry parentChildOrderRegistry() {
      return mock(ParentChildOrderRegistry.class);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ParentChildOrderRegistry registry;

  @Test
  void returnsProgressForKnownParent() throws Exception {
    var parentId = "parent-uuid";
    var progress = new IcebergProgress(6000, 1000, 2000, 1500, 2, "child-uuid");
    when(registry.progress(parentId)).thenReturn(Optional.of(progress));

    mockMvc
        .perform(get("/api/execution/orders/{id}/iceberg-progress", parentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parentOrderId").value("parent-uuid"))
        .andExpect(jsonPath("$.totalQuantity").value(6000))
        .andExpect(jsonPath("$.displayQuantity").value(1000))
        .andExpect(jsonPath("$.submittedQuantity").value(2000))
        .andExpect(jsonPath("$.filledQuantity").value(1500))
        .andExpect(jsonPath("$.slicesSubmitted").value(2))
        .andExpect(jsonPath("$.activeChildOrderId").value("child-uuid"));
  }

  @Test
  void returns404ForUnknownParent() throws Exception {
    when(registry.progress("unknown")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/execution/orders/{id}/iceberg-progress", "unknown"))
        .andExpect(status().isNotFound());
  }
}
