package com.mariaalpha.executionengine.controller;

import com.mariaalpha.executionengine.basket.BasketView;
import com.mariaalpha.executionengine.controller.dto.BasketOrderRequest;
import com.mariaalpha.executionengine.service.BasketTradingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/execution/baskets")
@Tag(name = "Basket", description = "Program/basket order submission and aggregate progress")
public class BasketController {

  private final BasketTradingService service;

  public BasketController(BasketTradingService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(summary = "Submit a program/basket order (all legs fanned out simultaneously)")
  public ResponseEntity<BasketView> submit(@Valid @RequestBody BasketOrderRequest request) {
    return ResponseEntity.accepted().body(service.submit(request));
  }

  @GetMapping("/{basketId}")
  @Operation(summary = "Return aggregate progress for a basket order")
  public ResponseEntity<BasketView> get(@PathVariable String basketId) {
    return service
        .get(basketId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping
  @Operation(summary = "List all tracked basket orders")
  public List<BasketView> list() {
    return service.list();
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(e.getMessage());
  }
}
