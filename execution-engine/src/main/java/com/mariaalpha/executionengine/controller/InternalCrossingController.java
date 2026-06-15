package com.mariaalpha.executionengine.controller;

import com.mariaalpha.executionengine.crossing.CrossingStats;
import com.mariaalpha.executionengine.crossing.InternalCrossingEngine;
import com.mariaalpha.executionengine.crossing.InternalCrossingEngine.BookSide;
import com.mariaalpha.executionengine.crossing.MidpointCross;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/execution/internal-crossing")
@Tag(
    name = "Internal Crossing",
    description = "Resting interest, recent crosses, and spread-capture stats for INTERNAL_CROSS")
@Profile("simulated")
public class InternalCrossingController {

  private final InternalCrossingEngine engine;

  public InternalCrossingController(InternalCrossingEngine engine) {
    this.engine = engine;
  }

  @Operation(summary = "Aggregate stats: crosses, shares matched, spread captured, resting depth")
  @GetMapping("/stats")
  public CrossingStats stats() {
    return engine.stats();
  }

  @Operation(summary = "Live snapshot of per-symbol resting interest in the internal book")
  @GetMapping("/book")
  public Map<String, BookSide> book() {
    return engine.bookSnapshot();
  }

  @Operation(summary = "Last N crosses (newest first), capped by the engine's internal ring")
  @GetMapping("/recent")
  public List<MidpointCross> recent() {
    return engine.recentCrosses();
  }
}
