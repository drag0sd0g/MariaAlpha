package com.mariaalpha.strategyengine.algo;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for programmatic algorithmic-order submission (roadmap 3.4.4). Sits alongside the
 * strategy-binding endpoints under {@code /api/strategies/*}: where those bind a strategy to a
 * symbol persistently, this one submits a single named parent order with target quantity and gets a
 * UUID back that the caller can poll / cancel.
 *
 * <p>The contract is intentionally simple — POST to create, GET to query, DELETE to cancel. Lives
 * under the api-gateway's {@code /api/algo/**} route (configured in api-gateway/application.yml).
 */
@RestController
@RequestMapping("/api/algo/orders")
public class AlgoOrderController {

  private final AlgoOrderService service;
  private final AlgoOrderRegistry registry;

  public AlgoOrderController(AlgoOrderService service, AlgoOrderRegistry registry) {
    this.service = service;
    this.registry = registry;
  }

  @PostMapping
  public ResponseEntity<AlgoOrderResponse> submit(@Valid @RequestBody AlgoOrderRequest request) {
    var order = service.submit(request);
    return ResponseEntity.created(URI.create("/api/algo/orders/" + order.algoOrderId()))
        .body(AlgoOrderResponse.from(order));
  }

  @GetMapping("/{id}")
  public ResponseEntity<AlgoOrderResponse> get(@PathVariable UUID id) {
    return registry
        .find(id)
        .map(AlgoOrderResponse::from)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping
  public List<AlgoOrderResponse> list() {
    return registry.all().stream().map(AlgoOrderResponse::from).toList();
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<AlgoOrderResponse> cancel(@PathVariable UUID id) {
    return service
        .cancel(id)
        .map(AlgoOrderResponse::from)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(e.getMessage());
  }
}
