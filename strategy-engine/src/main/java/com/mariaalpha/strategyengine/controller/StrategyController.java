package com.mariaalpha.strategyengine.controller;

import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/strategies")
public class StrategyController {
  private final StrategyRegistry registry;
  private final SymbolStrategyRouter router;
  private final MlSignalClient mlClient;

  public StrategyController(
      StrategyRegistry registry, SymbolStrategyRouter router, MlSignalClient mlClient) {
    this.registry = registry;
    this.router = router;
    this.mlClient = mlClient;
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

  @DeleteMapping("/{symbol}")
  public ResponseEntity<Void> clearActiveStrategy(@PathVariable String symbol) {
    boolean removed = router.clearActiveStrategy(symbol);
    return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
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

  /**
   * Aggregated per-symbol state for the UI Strategy Control page. Returns one row per routed symbol
   * when {@code symbol} is omitted, or just that symbol when provided. ML signal / regime fields
   * are nullable so the UI can render "—" when the model is unavailable.
   */
  @GetMapping("/state")
  public List<StrategyStateResponse> state(@RequestParam(required = false) String symbol) {
    if (symbol != null && !symbol.isBlank()) {
      return List.of(buildState(symbol));
    }
    return router.routedSymbols().stream().sorted().map(this::buildState).toList();
  }

  private StrategyStateResponse buildState(String symbol) {
    var active = router.getActiveStrategyName(symbol).orElse(null);
    var signal =
        mlClient
            .getSignal(symbol)
            .map(r -> new StrategyStateResponse.Signal(r.direction().name(), r.confidence()))
            .orElse(null);
    var regime =
        mlClient
            .getRegime(symbol)
            .map(r -> new StrategyStateResponse.Regime(r.regime().name(), r.confidence()))
            .orElse(null);
    return new StrategyStateResponse(symbol, active, signal, regime);
  }
}
