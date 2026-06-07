package com.mariaalpha.posttrade.controller;

import com.mariaalpha.posttrade.allocation.AllocationRequest;
import com.mariaalpha.posttrade.allocation.AllocationService;
import com.mariaalpha.posttrade.allocation.SubAccountRegistry;
import com.mariaalpha.posttrade.controller.dto.AllocationRequestDto;
import com.mariaalpha.posttrade.controller.dto.AllocationResponse;
import com.mariaalpha.posttrade.controller.dto.SubAccountResponse;
import com.mariaalpha.posttrade.repository.AllocationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface for the trade-allocation engine (roadmap 3.4.2).
 *
 * <ul>
 *   <li>{@code GET /api/allocations/sub-accounts} — list the configured roster.
 *   <li>{@code POST /api/allocations/run} — allocate a parent order across sub-accounts.
 *   <li>{@code GET /api/allocations/order/{orderId}} — fetch allocations for a parent.
 *   <li>{@code GET /api/allocations/sub-account/{name}} — fetch allocations for a sub-account.
 * </ul>
 */
@RestController
@RequestMapping("/api/allocations")
public class AllocationController {

  private final AllocationService allocationService;
  private final SubAccountRegistry registry;
  private final AllocationRepository repository;

  public AllocationController(
      AllocationService allocationService,
      SubAccountRegistry registry,
      AllocationRepository repository) {
    this.allocationService = allocationService;
    this.registry = registry;
    this.repository = repository;
  }

  @GetMapping("/sub-accounts")
  public SubAccountResponse subAccounts() {
    return SubAccountResponse.from(registry);
  }

  @PostMapping("/run")
  public ResponseEntity<List<AllocationResponse>> run(@RequestBody AllocationRequestDto request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body required");
    }
    try {
      var serviceRequest =
          new AllocationRequest(
              request.orderId(),
              request.symbol(),
              request.side(),
              request.parentFilledQuantity(),
              request.parentAvgPrice(),
              request.method());
      var saved = allocationService.allocate(serviceRequest);
      var body = saved.stream().map(AllocationResponse::of).toList();
      return ResponseEntity.status(HttpStatus.CREATED).body(body);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
  }

  @GetMapping("/order/{orderId}")
  public List<AllocationResponse> byOrder(@PathVariable UUID orderId) {
    return repository.findByOrderIdOrderBySubAccount(orderId).stream()
        .map(AllocationResponse::of)
        .toList();
  }

  @GetMapping("/sub-account/{name}")
  public List<AllocationResponse> bySubAccount(@PathVariable String name) {
    return repository.findBySubAccountOrderByAllocatedAtDesc(name).stream()
        .map(AllocationResponse::of)
        .toList();
  }
}
