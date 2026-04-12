package com.mariaalpha.strategyengine.controller;

import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/strategies")
public class StrategyController {
  private final StrategyRegistry registry;

  public StrategyController(StrategyRegistry registry) {
    this.registry = registry;
  }

  @GetMapping
  public Set<String> listStrategies() {
    return registry.availableStrategies();
  }
}
