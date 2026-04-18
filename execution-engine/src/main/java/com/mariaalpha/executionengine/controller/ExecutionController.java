package com.mariaalpha.executionengine.controller;

import com.mariaalpha.executionengine.risk.DailyLossMonitor;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/execution")
public class ExecutionController {

  private final DailyLossMonitor dailyLossMonitor;

  public ExecutionController(DailyLossMonitor dailyLossMonitor) {
    this.dailyLossMonitor = dailyLossMonitor;
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
}
