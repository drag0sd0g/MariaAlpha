package com.mariaalpha.ordermanager.controller;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.mariaalpha.ordermanager.controller.dto.PositionResponse;
import com.mariaalpha.ordermanager.repository.PositionRepository;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/positions")
public class PositionController {
  private final PositionRepository positionRepository;

  public PositionController(PositionRepository positionRepository) {
    this.positionRepository = positionRepository;
  }

  @GetMapping
  public List<PositionResponse> list() {
    return positionRepository.findAll().stream().map(PositionResponse::of).toList();
  }

  @GetMapping("/{symbol}")
  public ResponseEntity<PositionResponse> getOne(@PathVariable String symbol) {
    return positionRepository
        .findById(symbol)
        .map(PositionResponse::of)
        .map(ResponseEntity::ok)
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Position not found"));
  }
}
