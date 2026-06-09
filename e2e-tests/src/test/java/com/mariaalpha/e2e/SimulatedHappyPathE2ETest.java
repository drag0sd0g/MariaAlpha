package com.mariaalpha.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatedHappyPathE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final String apiKey = SharedComposeStack.get().apiKey();
    private final String baseUrl = SharedComposeStack.get().gatewayBaseUrl();
    private HttpClient httpClient;

    @BeforeAll
    void startStack() {
        // The shared compose stack is JVM-global; whichever e2e class fires first pays the
        // ~2-minute startup cost, the rest no-op. Teardown is JVM-shutdown hooked.
        SharedComposeStack.get().start();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void unbindStatefulStrategies() {
        // MOMENTUM is stateful — once bound to GOOGL it enters a long position on the bullish EMA
        // crossover and then oscillates entry/exit every few ticks for the rest of the JVM's life,
        // saturating the simulated INTERNAL_CROSS queue. Detach it after each test so the next
        // test's symbol-specific orders don't compete with a background oscillation. The unbind
        // is best-effort and ignores 404s (the strategy may not have been bound this method).
        for (String symbol : new String[] {"GOOGL", "AAPL", "MSFT", "AMZN", "TSLA", "NVDA"}) {
            try {
                httpClient.send(HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/api/strategies/" + symbol))
                                .header("X-API-Key", apiKey)
                                .DELETE()
                                .timeout(Duration.ofSeconds(3))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // Best-effort cleanup — never fail a test on this.
            }
        }
    }

    @Test
    void simulatedTickReachesPositionWithFillAndPnl() throws Exception {
        bindVwapToAppleWith100Shares();

        // Gate on the VWAP-specific FILLED order rather than the AAPL position. If
        // rfqQuoteReturnsTwoWayBookAndAcceptPublishesOrderSignal ran first in this stack lifecycle
        // (JUnit method order is not guaranteed), it leaves an AAPL position behind that would
        // satisfy a netQuantity != 0 wait before VWAP has had a chance to fire. Mirrors the pattern
        // simulatedMomentumUptrendReachesGooglOrderWithFill uses (firstFilledMomentumBuy).
        var order = await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(this::firstFilledVwapAaplBuy, Objects::nonNull);

        assertThat(order.get("status").asText()).isEqualTo("FILLED");
        assertThat(order.get("strategy").asText()).isEqualTo("VWAP");
        assertThat(new BigDecimal(order.get("quantity").asText()))
                .as("VWAP bin trades the configured 100-share clip")
                .isEqualByComparingTo(new BigDecimal("100"));

        var position = getPosition("AAPL");
        assertThat(position).as("AAPL position must exist after VWAP fill").isNotNull();
        // Net quantity is at least 100 (this VWAP fill). If the RFQ test ran first it adds another
        // 100 from its accepted quote, so accept >= 100 rather than == 100.
        assertThat(netQuantity(position)).isGreaterThanOrEqualTo(new BigDecimal("100"));

        var avgEntry = new BigDecimal(position.get("avgEntryPrice").asText());
        assertThat(avgEntry).as("avgEntryPrice within CSV bid/ask span ± slippage")
                .isBetween(new BigDecimal("178.40"), new BigDecimal("178.65"));

        assertThat(position.has("unrealizedPnl")).isTrue();
        assertThat(position.has("lastMarkPrice")).isTrue();
        assertThat(new BigDecimal(position.get("realizedPnl").asText())).isEqualByComparingTo("0");

        var portfolio = httpGetAndCheck("/api/portfolio/summary");
        // >= 1 rather than == 1: the TWAP (MSFT) and Momentum (GOOGL) tests in this class may have
        // opened other positions too, and test method order is not guaranteed.
        assertThat(portfolio.get("openPositions").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(portfolio.has("totalPnl")).isTrue();

        var status = httpGetAndCheck("/api/execution/status");
        assertThat(status.get("tradingHalted").asBoolean())
                .as("daily loss limit should not have tripped on a single 100-share fill")
                .isFalse();
    }

    private JsonNode firstFilledVwapAaplBuy() throws Exception {
        var orders = httpGetAndCheck("/api/orders?symbol=AAPL&strategy=VWAP&status=FILLED");
        if (!orders.isArray()) {
            return null;
        }
        for (var order : orders) {
            if ("BUY".equals(order.get("side").asText())) {
                return order;
            }
        }
        return null;
    }

    @Test
    void simulatedTwapTickReachesMsftPositionWithFill() throws Exception {
        bindTwapToMsftWith50Shares();

        var position = awaitPositionAtLeast("MSFT", new BigDecimal("50"), 45);

        assertThat(netQuantity(position)).as("MSFT net quantity should be 50 after TWAP slice fires")
                .isEqualByComparingTo(new BigDecimal("50"));

        var avgEntry = new BigDecimal(position.get("avgEntryPrice").asText());
        assertThat(avgEntry).as("avgEntryPrice within CSV bid/ask span ± slippage")
                .isBetween(new BigDecimal("415.00"), new BigDecimal("416.00"));

        var orders = httpGetAndCheck("/api/orders?symbol=MSFT");
        assertThat(orders.isArray()).isTrue();
        assertThat(orders.size()).isGreaterThanOrEqualTo(1);
        var order = orders.get(0);
        assertThat(order.get("status").asText()).isEqualTo("FILLED");
        assertThat(order.get("strategy").asText()).isEqualTo("TWAP");
    }

    @Test
    void simulatedShortfallTickReachesAmznPositionWithFill() throws Exception {
        bindImplementationShortfallToAmznWith40Shares();

        var position = awaitPositionAtLeast("AMZN", new BigDecimal("40"), 45);

        assertThat(netQuantity(position)).as("AMZN net quantity should be 40 after the IS slice fires")
                .isEqualByComparingTo(new BigDecimal("40"));

        var avgEntry = new BigDecimal(position.get("avgEntryPrice").asText());
        assertThat(avgEntry).as("avgEntryPrice within CSV bid/ask span ± slippage")
                .isBetween(new BigDecimal("185.00"), new BigDecimal("185.50"));

        var orders = httpGetAndCheck("/api/orders?symbol=AMZN");
        assertThat(orders.isArray()).isTrue();
        assertThat(orders.size()).isGreaterThanOrEqualTo(1);
        var order = orders.get(0);
        assertThat(order.get("status").asText()).isEqualTo("FILLED");
        assertThat(order.get("strategy").asText()).isEqualTo("IS");
    }

    @Test
    void simulatedCloseTickReachesNvdaPositionWithMocFill() throws Exception {
        bindCloseToNvdaWith45SharesMocOnly();

        // Gate on the persisted FILLED CLOSE MARKET order rather than a netQuantity == 45 check.
        // InternalCrossingE2ETest submits 10 × (SELL 10 NVDA, BUY 10 NVDA) against the SAME
        // shared compose stack, and SOR-routing variation can leave the pairs slightly imbalanced
        // (e.g. one of the 10 BUYs doesn't land), so the NVDA position seen here is the CLOSE
        // BUY 45 *plus* whatever residual InternalCrossingE2ETest left behind. The CLOSE path's
        // correctness is the order's shape (45-share MARKET BUY filled with strategy=CLOSE),
        // which is invariant to that. Mirrors the pattern simulatedTickReachesPositionWithFillAndPnl
        // uses (firstFilledVwapAaplBuy).
        var order = await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(this::firstFilledCloseNvdaBuy, Objects::nonNull);

        assertThat(order.get("side").asText()).isEqualTo("BUY");
        assertThat(order.get("status").asText()).isEqualTo("FILLED");
        assertThat(order.get("strategy").asText()).isEqualTo("CLOSE");
        assertThat(order.get("orderType").asText()).isEqualTo("MARKET");
        assertThat(new BigDecimal(order.get("quantity").asText()))
                .as("CLOSE MOC trades the configured 45-share parent in one MARKET clip")
                .isEqualByComparingTo(new BigDecimal("45"));

        // Position must exist; avgEntryPrice should reflect the CSV NVDA bid/ask band (the CLOSE
        // BUY is the dominant flow on this symbol so avgEntryPrice is dominated by 875.20).
        var position = getPosition("NVDA");
        assertThat(position).as("NVDA position must exist after CLOSE fill").isNotNull();
        var avgEntry = new BigDecimal(position.get("avgEntryPrice").asText());
        assertThat(avgEntry).as("avgEntryPrice within CSV bid/ask span ± slippage")
                .isBetween(new BigDecimal("875.00"), new BigDecimal("876.00"));
    }

    private JsonNode firstFilledCloseNvdaBuy() throws Exception {
        var orders = httpGetAndCheck("/api/orders?symbol=NVDA&strategy=CLOSE&status=FILLED");
        if (!orders.isArray()) {
            return null;
        }
        for (var o : orders) {
            if ("BUY".equals(o.get("side").asText())) {
                return o;
            }
        }
        return null;
    }

    @Test
    void simulatedPovTickReachesTslaPositionWithFill() throws Exception {
        bindPovToTslaWith60Shares();

        // POV emits multiple LIMIT clips proportional to traded volume on the tape — a 60-share
        // parent at 20% participation against 300/250/500/350-share TRADE prints completes in
        // one or two clips depending on tick ordering. Wait for the full parent before asserting
        // so a mid-completion poll doesn't see e.g. 50/60.
        var position = awaitPositionAtLeast("TSLA", new BigDecimal("60"), 45);

        assertThat(netQuantity(position)).as("TSLA net quantity should reach the 60-share POV parent")
                .isEqualByComparingTo(new BigDecimal("60"));

        var avgEntry = new BigDecimal(position.get("avgEntryPrice").asText());
        assertThat(avgEntry).as("avgEntryPrice within CSV bid/ask span ± slippage")
                .isBetween(new BigDecimal("245.00"), new BigDecimal("246.00"));

        var orders = httpGetAndCheck("/api/orders?symbol=TSLA&strategy=POV");
        assertThat(orders.isArray()).isTrue();
        assertThat(orders.size()).isGreaterThanOrEqualTo(1);
        for (var order : orders) {
            assertThat(order.get("strategy").asText()).isEqualTo("POV");
            assertThat(order.get("status").asText()).isEqualTo("FILLED");
        }
    }

    @Test
    void rfqQuoteReturnsTwoWayBookAndAcceptPublishesOrderSignal() throws Exception {
        // The simulator is publishing AAPL ticks into market-data.ticks, so the strategy-engine's
        // MarketStateCache populates within a few seconds of stack startup. Poll the RFQ endpoint
        // until the gateway accepts the request (it 503s while no book is available).
        var quote = await().atMost(45, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(this::requestRfqQuoteForAapl, Objects::nonNull);

        assertThat(quote.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(quote.get("quantity").asInt()).isEqualTo(100);
        var bid = new BigDecimal(quote.get("bid").asText());
        var ask = new BigDecimal(quote.get("ask").asText());
        assertThat(bid).as("bid < ask").isLessThan(ask);
        assertThat(quote.get("breakdown").get("baseHalfSpreadBps").asDouble())
                .as("base half-spread comes from application.yml")
                .isEqualTo(2.0);
        // 2.4.2: per-symbol ADV is wired (AAPL → 60M), so we expect ADV widening to be reported (>=0)
        assertThat(quote.get("breakdown").get("advShares").asLong()).isEqualTo(60_000_000L);

        // Accept BUY at the quoted ask → strategy-engine publishes an OrderSignal with strategyName=RFQ.
        var acceptBody = MAPPER.writeValueAsString(Map.of(
                "quoteId", quote.get("quoteId").asText(),
                "side", "BUY",
                "price", quote.get("ask").asText()));
        var resp = httpPostAndCheck("/api/rfq/accept", acceptBody);
        assertThat(resp.get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void eodReconciliationProducesCleanRunInMirrorMode() throws Exception {
        // Ensure at least one fill has landed before triggering recon, otherwise the matcher has
        // nothing to compare. Either of the other tests in this class will satisfy this — gate on
        // an AAPL FILLED order rather than running our own strategy bind.
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> {
                    var orders = httpGetAndCheck("/api/orders?status=FILLED");
                    return orders.isArray() && orders.size() > 0;
                });

        // Trigger today's recon (UTC — fills land with Instant.now() which is UTC-based).
        // The simulated stack runs in MIRROR mode so internal fills are echoed back as external —
        // expect SUCCESS and 0 breaks.
        var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        var resp = httpPostAndCheck("/api/recon/run?date=" + today, "{}");
        assertThat(resp.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(resp.get("source").asText()).isEqualTo("MANUAL");
        assertThat(resp.get("breaksCount").asInt())
                .as("MIRROR mode echoes internal fills, so the comparator must produce no breaks")
                .isZero();
        assertThat(resp.get("internalFillsCount").asInt())
                .as("at least one fill should have been visible to the comparator")
                .isGreaterThan(0);

        // Summary now reflects the run record.
        var summary = httpGetAndCheck("/api/recon/summary?date=" + today);
        assertThat(summary.get("totalBreaks").asInt()).isZero();
        assertThat(summary.get("run").get("status").asText()).isEqualTo("SUCCESS");

        // Run record listed by /api/recon/runs
        var runs = httpGetAndCheck("/api/recon/runs");
        assertThat(runs.isArray()).isTrue();
        assertThat(runs.size()).isGreaterThanOrEqualTo(1);
    }

    private JsonNode requestRfqQuoteForAapl() throws Exception {
        var requestBody = "{\"symbol\":\"AAPL\",\"quantity\":100}";
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/rfq/quote"))
                        .header("X-API-Key", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return MAPPER.readTree(response.body());
    }

    private JsonNode httpPostAndCheck(String requestPath, String requestBody) throws Exception {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + requestPath))
                        .header("X-API-Key", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST %s body=%s → %s", requestPath, requestBody, response.body())
                .isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    @Test
    void simulatedMomentumUptrendReachesGooglOrderWithFill() throws Exception {
        bindMomentumToGoogl();

        // As the simulated GOOGL uptrend's fast EMA crosses above the slow EMA, Momentum opens a
        // long position. A later loop's reverse-crossover may flatten it, so we gate on the
        // persisted (append-only) FILLED BUY order rather than a live, possibly-oscillating net
        // position.
        var order = await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(this::firstFilledMomentumBuy, Objects::nonNull);

        assertThat(order.get("side").asText()).isEqualTo("BUY");
        assertThat(order.get("status").asText()).isEqualTo("FILLED");
        assertThat(order.get("strategy").asText()).isEqualTo("MOMENTUM");
        assertThat(order.get("symbol").asText()).isEqualTo("GOOGL");
        assertThat(new BigDecimal(order.get("quantity").asText()))
                .as("Momentum entry trades the configured clip size")
                .isEqualByComparingTo(new BigDecimal("30"));

        var status = httpGetAndCheck("/api/execution/status");
        assertThat(status.get("tradingHalted").asBoolean())
                .as("a small momentum round-trip must not trip the daily loss limit")
                .isFalse();
    }

    private JsonNode firstFilledMomentumBuy() throws Exception {
        var orders = httpGetAndCheck("/api/orders?symbol=GOOGL&strategy=MOMENTUM&status=FILLED");
        if (!orders.isArray()) {
            return null;
        }
        for (var order : orders) {
            if ("BUY".equals(order.get("side").asText())) {
                return order;
            }
        }
        return null;
    }

    private void bindMomentumToGoogl() throws Exception {
        httpPutAndCheck("/api/strategies/GOOGL", "{\"strategyName\":\"MOMENTUM\"}");
        // Tiny EMA periods + a 2-trade warmup so the simulated GOOGL uptrend produces a prompt
        // bullish crossover; stop-loss disabled (0) to keep the entry deterministic.
        //
        // volumeMultiplier=0 disables the volume-confirmation gate, which is otherwise
        // flake-prone in this loop-replay setup: if the strategy binds mid-loop instead of in
        // the inter-loop quiet window, MomentumStrategy seeds its rolling volume baseline with
        // the 400-share GOOGL trades and can never satisfy `size > 1.5 × avg` thereafter — the
        // state persists across CSV iterations and the strategy is locked out. Volume gating
        // is exercised by MomentumStrategyTest (bullishCrossoverFailsWithoutVolumeConfirmation);
        // this e2e covers the signal→execution→fill→DB→API path, not the gate.
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "fastPeriod", 2,
                    "slowPeriod", 3,
                    "warmupTrades", 2,
                    "rsiPeriod", 14,
                    "volumeMultiplier", 0.0,
                    "tradeQuantity", 30,
                    "side", "BUY",
                    "stopLossPct", 0.0));
        // Parameters are addressed by strategy name, not symbol
        httpPutAndCheck("/api/strategies/MOMENTUM/parameters", params);
    }

    private void bindCloseToNvdaWith45SharesMocOnly() throws Exception {
        httpPutAndCheck("/api/strategies/NVDA", "{\"strategyName\":\"CLOSE\"}");
        // CSV NVDA ticks land at 14:30:02.500-14:30:04.500 UTC == 10:30:02.5-10:30:04.5 ET. The
        // strategy is configured so its 60-second "session" wraps that interval: windowStart at
        // 10:30:00, closeTime at 10:31:00, with a 1-minute MOC offset → mocCutoff = 10:30:00. Any
        // NVDA tick reaching the strategy is past the cutoff, so the MOC fires the full 45-share
        // parent in one MARKET order. preCloseFraction = 0 → pure MOC behaviour (the multi-slice
        // pre-close schedule is exercised by the unit + integration tests instead).
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "targetQuantity", 45,
                    "side", "BUY",
                    "windowStart", "10:30:00",
                    "closeTime", "10:31:00",
                    "mocOffsetMinutes", 1,
                    "preCloseFraction", 0.0,
                    "numPreCloseSlices", 6));
        // Parameters are addressed by strategy name, not symbol
        httpPutAndCheck("/api/strategies/CLOSE/parameters", params);
    }

    private void bindPovToTslaWith60Shares() throws Exception {
        httpPutAndCheck("/api/strategies/TSLA", "{\"strategyName\":\"POV\"}");
        // 60-share parent at 20% participation over the same 10:30:00-10:31:00 ET window
        // the simulated CSV TSLA ticks fall into. CSV TRADEs are 300/250/500/350 shares (1400
        // cumulative) → 20% participation yields ~280 expected; the 60-share parent caps it.
        // The first 300-share print alone produces a 60-share clip — POV completes in one shot.
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "targetQuantity", 60,
                    "side", "BUY",
                    "startTime", "10:30:00",
                    "endTime", "10:31:00",
                    "participationRate", 0.20,
                    "minClipSize", 5,
                    "maxClipSize", 1_000_000));
        // Parameters are addressed by strategy name, not symbol
        httpPutAndCheck("/api/strategies/POV/parameters", params);
    }

    private void bindImplementationShortfallToAmznWith40Shares() throws Exception {
        httpPutAndCheck("/api/strategies/AMZN", "{\"strategyName\":\"IS\"}");
        // Single slice over the one-minute window the simulated CSV AMZN ticks fall into
        // (CSV AMZN ticks are 14:30:00-14:30:02Z == 10:30:00-10:30:02 America/New_York). With one
        // slice the Almgren-Chriss front-load collapses to the whole clip; the multi-slice
        // front-loading shape is exercised by the unit + integration tests instead.
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "targetQuantity", 40,
                    "side", "BUY",
                    "startTime", "10:30:00",
                    "endTime", "10:31:00",
                    "numSlices", 1,
                    "urgency", 0.5));
        // Parameters are addressed by strategy name, not symbol
        httpPutAndCheck("/api/strategies/IS/parameters", params);
    }

    private void bindTwapToMsftWith50Shares() throws Exception {
        httpPutAndCheck("/api/strategies/MSFT", "{\"strategyName\":\"TWAP\"}");
        // Single slice over the one-minute window that the simulated CSV ticks fall into
        // (CSV MSFT ticks are 14:30:00-14:30:02Z == 10:30:00-10:30:02 America/New_York).
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "targetQuantity", 50,
                    "side", "BUY",
                    "startTime", "10:30:00",
                    "endTime", "10:31:00",
                    "numSlices", 1));
        // Parameters are addressed by strategy name, not symbol
        httpPutAndCheck("/api/strategies/TWAP/parameters", params);
    }

    private void bindVwapToAppleWith100Shares() throws Exception {
        httpPutAndCheck("/api/strategies/AAPL", "{\"strategyName\":\"VWAP\"}");
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "targetQuantity", 100,
                    "side", "BUY",
                    "startTime", "10:30:00",
                    "endTime", "10:31:00",
                    "volumeProfile", List.of(
                            Map.of(
                                    "startTime", "10:30:00",
                                    "endTime", "10:31:00",
                                    "volumeFraction", 1.0))
                                )
        );
        // Parameters are addressed by strategy name, not symbol
        httpPutAndCheck("/api/strategies/VWAP/parameters", params);
    }

    private static BigDecimal netQuantity(JsonNode position){
        return new BigDecimal(position.get("netQuantity").asText());
    }

    /**
     * Poll the position endpoint until the symbol's net quantity reaches {@code minTarget} (or
     * beyond). Replaces the older pattern of waiting on {@code netQuantity != 0} then asserting an
     * exact target — multi-clip strategies (POV) and slippage-delayed single-clip fills (TWAP,
     * IS, CLOSE) can both partially fill before the full target lands, racing the assertion.
     */
    private JsonNode awaitPositionAtLeast(String symbol, BigDecimal minTarget, int atMostSeconds) {
        return await().atMost(atMostSeconds, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(
                        () -> getPosition(symbol),
                        pos -> pos != null && netQuantity(pos).compareTo(minTarget) >= 0);
    }

    private JsonNode getPosition(String symbol) throws Exception {
        var requestPath = "/api/positions/" + symbol;
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + requestPath))
                        .header("X-API-Key", apiKey)
                        .GET()
                        .timeout(Duration.ofSeconds(3))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if(response.statusCode() == 404){
            return null; // position not yet created
        }
        if(response.statusCode() != 200){
            throw new IllegalStateException("GET " + requestPath + " → " + response.statusCode() + " body="+response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private JsonNode httpGetAndCheck(String requestPath) throws Exception {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + requestPath))
                        .header("X-API-Key", apiKey)
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("GET %s → %s", requestPath, response.body())
                .isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private void httpPutAndCheck(String requestPath, String requestBody) throws Exception{
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + requestPath))
                        .header("X-API-Key", apiKey)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("PUT %s → %s", requestPath, response.body()).isBetween(200, 204);
    }

}