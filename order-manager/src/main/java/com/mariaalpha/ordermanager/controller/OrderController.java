package com.mariaalpha.ordermanager.controller;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.mariaalpha.ordermanager.controller.dto.FillResponse;
import com.mariaalpha.ordermanager.controller.dto.OrderResponse;
import com.mariaalpha.ordermanager.model.OrderStatus;
import com.mariaalpha.ordermanager.repository.FillRepository;
import com.mariaalpha.ordermanager.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private static final String DEFAULT_LIMIT = "100";
  private static final int MAX_LIMIT = 500;

  private final OrderRepository orderRepository;
  private final FillRepository fillRepository;

  public OrderController(OrderRepository orderRepository, FillRepository fillRepository) {
    this.orderRepository = orderRepository;
    this.fillRepository = fillRepository;
  }

  @GetMapping
  public List<OrderResponse> list(
      @RequestParam(required = false) String symbol,
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) String strategy,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false, defaultValue = DEFAULT_LIMIT) int limit) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new ResponseStatusException(
          BAD_REQUEST, "'from' timestamp must be before 'to' timestamp");
    }
    // Clamp caller-supplied limit to [1, MAX_LIMIT] to guard against invalid or abusive page sizes.
    int clamped = Math.clamp(limit, 1, MAX_LIMIT);
    return orderRepository
        .search(symbol, status, strategy, from, to, PageRequest.of(0, clamped))
        .stream()
        .map(OrderResponse::of)
        .toList();
  }

  @GetMapping("/{orderId}")
  public ResponseEntity<OrderResponse> getOne(@PathVariable UUID orderId) {
    var order =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Order not found"));
    List<FillResponse> fills =
        fillRepository.findByOrder_OrderIdOrderByFilledAtAsc(orderId).stream()
            .map(FillResponse::of)
            .toList();
    return ResponseEntity.ok(OrderResponse.of(order, fills));
  }
}
