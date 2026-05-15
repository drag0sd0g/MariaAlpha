package com.mariaalpha.executionengine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.adapter.VenueAdapter;
import com.mariaalpha.executionengine.adapter.VenueAdapterRegistry;
import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.controller.dto.RoutingPreviewRequest;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.RoutingDecision;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.router.RoutingDecisionCache;
import com.mariaalpha.executionengine.router.Venue;
import com.mariaalpha.executionengine.router.VenueRegistry;
import com.mariaalpha.executionengine.router.VenueScoreBreakdown;
import com.mariaalpha.executionengine.router.VenueScorer;
import com.mariaalpha.executionengine.router.VenueType;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoutingControllerTest {

  private VenueRegistry registry;
  private VenueScorer scorer;
  private RoutingDecisionCache cache;
  private MarketStateTracker tracker;
  private VenueAdapterRegistry venueAdapters;
  private MockMvc mvc;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    var weights = new SorConfig.Weights(0.25, 0.25, 0.10, 0.15, 0.25);
    var v = new Venue("PRIMARY", VenueType.LIT, 50, 3, 2, 1.0, 10000, 0.95, "primary", true);
    registry = new VenueRegistry(new SorConfig("scored", 200, 10, 5, 1000, weights, List.of(v)));
    registry.validate();
    scorer = mock(VenueScorer.class);
    cache = mock(RoutingDecisionCache.class);
    tracker = mock(MarketStateTracker.class);
    venueAdapters = mock(VenueAdapterRegistry.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new RoutingController(registry, scorer, cache, tracker, venueAdapters))
            .build();
  }

  @Test
  void listVenuesReturnsConfiguredVenues() throws Exception {
    mvc.perform(get("/api/routing/venues"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("PRIMARY"))
        .andExpect(jsonPath("$[0].type").value("LIT"));
  }

  @Test
  void getDecisionFound() throws Exception {
    var d = RoutingDecision.legacy("o-1", "PRIMARY", "x", Instant.now());
    when(cache.get("o-1")).thenReturn(Optional.of(d));
    mvc.perform(get("/api/routing/decisions/o-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.venue").value("PRIMARY"));
  }

  @Test
  void getDecisionNotFound() throws Exception {
    when(cache.get("o-2")).thenReturn(Optional.empty());
    mvc.perform(get("/api/routing/decisions/o-2")).andExpect(status().isNotFound());
  }

  @Test
  void scorePreviewHappy() throws Exception {
    when(scorer.score(any(), any(), any()))
        .thenReturn(new VenueScoreBreakdown("PRIMARY", VenueType.LIT, 0.7, Map.of()));
    var req = new RoutingPreviewRequest("AAPL", Side.BUY, OrderType.MARKET, 100, null, null);
    mvc.perform(
            post("/api/routing/score")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.suggestedVenue").value("PRIMARY"))
        .andExpect(jsonPath("$.candidateScores[0].weightedScore").value(0.7));
  }

  @Test
  void scorePreviewBadInput() throws Exception {
    // missing symbol → bean-validation 400
    var json = "{\"side\":\"BUY\",\"orderType\":\"MARKET\",\"quantity\":100}";
    mvc.perform(post("/api/routing/score").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isBadRequest());
  }

  @Test
  void venueHealthFoundUp() throws Exception {
    var adapter = mock(VenueAdapter.class);
    when(adapter.venueName()).thenReturn("PRIMARY");
    when(adapter.venueType()).thenReturn(VenueType.LIT);
    when(adapter.isHealthy()).thenReturn(true);
    when(venueAdapters.get("PRIMARY")).thenReturn(Optional.of(adapter));

    mvc.perform(get("/api/routing/venues/PRIMARY/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.venue").value("PRIMARY"))
        .andExpect(jsonPath("$.type").value("LIT"))
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void venueHealthNotFound() throws Exception {
    when(venueAdapters.get("MISSING")).thenReturn(Optional.empty());
    mvc.perform(get("/api/routing/venues/MISSING/health")).andExpect(status().isNotFound());
  }
}
