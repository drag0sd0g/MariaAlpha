package com.mariaalpha.posttrade.controller;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.mariaalpha.posttrade.controller.dto.TcaResponse;
import com.mariaalpha.posttrade.repository.TcaResultRepository;
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
@RequestMapping("/api/tca")
public class TcaController {

  private static final String DEFAULT_LIMIT = "100";
  private static final int MAX_LIMIT = 500;

  private final TcaResultRepository repository;

  public TcaController(TcaResultRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/{orderId}")
  public ResponseEntity<TcaResponse> getOne(@PathVariable UUID orderId) {
    return repository
        .findByOrderId(orderId)
        .map(TcaResponse::of)
        .map(ResponseEntity::ok)
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "TCA result not found"));
  }

  @GetMapping
  public List<TcaResponse> list(
      @RequestParam(required = false) String symbol,
      @RequestParam(required = false) String strategy,
      @RequestParam(required = false, defaultValue = DEFAULT_LIMIT) int limit) {
    int clamped = Math.clamp(limit, 1, MAX_LIMIT);
    return repository.search(symbol, strategy, PageRequest.of(0, clamped)).stream()
        .map(TcaResponse::of)
        .toList();
  }
}
