package com.mariaalpha.executionengine.controller;

import com.mariaalpha.executionengine.pegged.PeggedProgressView;
import com.mariaalpha.executionengine.pegged.PeggedRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/execution/orders")
@Tag(name = "Pegged", description = "PEGGED parent-order progress + re-peg activity")
public class PeggedController {

  private final PeggedRegistry registry;

  public PeggedController(PeggedRegistry registry) {
    this.registry = registry;
  }

  @GetMapping("/{parentId}/pegged-progress")
  @Operation(summary = "Return current progress for a PEGGED parent order")
  public ResponseEntity<PeggedProgressView> progress(@PathVariable String parentId) {
    return registry
        .progress(parentId)
        .map(p -> ResponseEntity.ok(PeggedProgressView.of(parentId, p)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
