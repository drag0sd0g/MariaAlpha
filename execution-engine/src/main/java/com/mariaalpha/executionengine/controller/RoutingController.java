package com.mariaalpha.executionengine.controller;

import static java.util.Comparator.comparingDouble;

import com.mariaalpha.executionengine.controller.dto.RoutingPreviewRequest;
import com.mariaalpha.executionengine.controller.dto.RoutingPreviewResponse;
import com.mariaalpha.executionengine.controller.dto.VenueResponse;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.router.RoutingDecisionCache;
import com.mariaalpha.executionengine.router.VenueRegistry;
import com.mariaalpha.executionengine.router.VenueScoreBreakdown;
import com.mariaalpha.executionengine.router.VenueScorer;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/routing")
@Tag(name = "Smart Order Router", description = "SOR introspection and what-if scoring")
public class RoutingController {

  private final VenueRegistry registry;
  private final VenueScorer scorer;
  private final RoutingDecisionCache cache;
  private final MarketStateTracker marketStateTracker;

  public RoutingController(
      VenueRegistry registry,
      VenueScorer scorer,
      RoutingDecisionCache cache,
      MarketStateTracker marketStateTracker) {
    this.registry = registry;
    this.scorer = scorer;
    this.cache = cache;
    this.marketStateTracker = marketStateTracker;
  }

  @Operation(
      summary =
          "What-if score: returns per-venue breakdown for a hypothetical order without submitting")
  @PostMapping("/score")
  public ResponseEntity<RoutingPreviewResponse> score(
      @Valid @RequestBody RoutingPreviewRequest req) {
    var signal =
        new OrderSignal(
            req.symbol(),
            req.side(),
            req.quantity(),
            req.orderType(),
            req.limitPrice(),
            req.stopPrice(),
            "PREVIEW",
            Instant.now());
    var order = new Order(signal);
    var market = marketStateTracker.getMarketState(req.symbol());
    var enabled = registry.enabled();
    if (enabled.isEmpty()) {
      return ResponseEntity.ok(new RoutingPreviewResponse(null, List.of(), "No enabled venues"));
    }
    List<VenueScoreBreakdown> breakdowns =
        enabled.stream().map(v -> scorer.score(order, v, market)).toList();
    var top =
        breakdowns.stream().max(comparingDouble(VenueScoreBreakdown::weightedScore)).orElseThrow();
    return ResponseEntity.ok(new RoutingPreviewResponse(top.venue(), breakdowns, "preview"));
  }

  @Operation(summary = "List configured venues with their scoring inputs")
  @GetMapping("/venues")
  public List<VenueResponse> listVenues() {
    return registry.all().stream().map(VenueResponse::from).toList();
  }

  @Operation(
      summary = "Last cached routing decision for an order (404 if not in the in-memory ring)")
  @GetMapping("/decisions/{orderId}")
  public ResponseEntity<?> getDecision(@PathVariable String orderId) {
    return cache
        .get(orderId)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
