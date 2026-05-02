package com.mariaalpha.executionengine.controller;

import com.mariaalpha.executionengine.controller.dto.SubmitOrderRequest;
import com.mariaalpha.executionengine.controller.dto.SubmitOrderResponse;
import com.mariaalpha.executionengine.risk.DailyLossMonitor;
import com.mariaalpha.executionengine.service.ManualOrderService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/execution")
public class ExecutionController {

  private final DailyLossMonitor dailyLossMonitor;
  private final ManualOrderService manualOrderService;

  public ExecutionController(
      DailyLossMonitor dailyLossMonitor, ManualOrderService manualOrderService) {
    this.dailyLossMonitor = dailyLossMonitor;
    this.manualOrderService = manualOrderService;
  }

  @PostMapping("/resume")
  public ResponseEntity<Map<String, String>> resume() {
    this.dailyLossMonitor.resume();
    return ResponseEntity.ok(
        Map.of("status", "resumed", "dailyPnl", dailyLossMonitor.getDailyPnl().toPlainString()));
  }

  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> status() {
    return ResponseEntity.ok(
        Map.of(
            "tradingHalted",
            this.dailyLossMonitor.isTradingHalted(),
            "dailyPnl",
            this.dailyLossMonitor.getDailyPnl().toPlainString()));
  }

  @PostMapping("/orders")
  public ResponseEntity<SubmitOrderResponse> submitOrder(
      @Valid @RequestBody SubmitOrderRequest request) {
    return ResponseEntity.accepted().body(manualOrderService.submit(request));
  }

  @DeleteMapping("/orders/{orderId}")
  public ResponseEntity<Void> cancelOrder(@PathVariable String orderId) {
    return manualOrderService.cancel(orderId)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}
