package com.mariaalpha.executionengine.controller;

import com.mariaalpha.executionengine.iceberg.IcebergProgressView;
import com.mariaalpha.executionengine.iceberg.ParentChildOrderRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/execution/orders")
@Tag(name = "Iceberg", description = "ICEBERG parent-order slice progress")
public class IcebergController {

  private final ParentChildOrderRegistry registry;

  public IcebergController(ParentChildOrderRegistry registry) {
    this.registry = registry;
  }

  @GetMapping("/{parentId}/iceberg-progress")
  @Operation(summary = "Return current slice progress for an ICEBERG parent order")
  public ResponseEntity<IcebergProgressView> progress(@PathVariable String parentId) {
    return registry
        .progress(parentId)
        .map(p -> ResponseEntity.ok(IcebergProgressView.of(parentId, p)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
