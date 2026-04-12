package com.mariaalpha.strategyengine.controller;

import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/strategies")
public class StrategyController {
  private final StrategyRegistry registry;
  private final SymbolStrategyRouter router;

  public StrategyController(StrategyRegistry registry, SymbolStrategyRouter router) {
    this.registry = registry;
    this.router = router;
  }

  @GetMapping
  public Set<String> listStrategies() {
    return registry.availableStrategies();
  }

  @PutMapping("/{symbol}")
  public ResponseEntity<Void> setActiveStrategy(
      @PathVariable String symbol, @RequestBody SetStrategyRequest request) {
    boolean ok = router.setActiveStrategy(symbol, request.strategyName());
    return ok ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
  }

  @GetMapping("/{symbol}/active")
  public ResponseEntity<String> getActiveStrategy(@PathVariable String symbol) {
    return router
        .getActiveStrategyName(symbol)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/{name}/parameters")
  public ResponseEntity<Map<String, Object>> getParameters(@PathVariable String name) {
    return registry
        .get(name)
        .map(TradingStrategy::getParameters)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @PutMapping("/{name}/parameters")
  public ResponseEntity<Object> updateParameters(
      @PathVariable String name, @RequestBody Map<String, Object> params) {
    return registry
        .get(name)
        .map(
            strategy -> {
              strategy.updateParameters(params);
              return ResponseEntity.<Void>ok().build();
            })
        .orElse(ResponseEntity.notFound().build());
  }
}
