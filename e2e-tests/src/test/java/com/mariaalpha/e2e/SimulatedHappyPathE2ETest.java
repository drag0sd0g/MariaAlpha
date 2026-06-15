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
        SharedComposeStack.get().start();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void unbindStatefulStrategies() {
        // test's symbol-specific orders don't compete with a background oscillation. The unbind
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
            }
        }
    }

    @Test
    void simulatedTickReachesPositionWithFillAndPnl() throws Exception {
        bindVwapToAppleWith100Shares();

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
        assertThat(netQuantity(position)).isGreaterThanOrEqualTo(new BigDecimal("100"));

        var avgEntry = new BigDecimal(position.get("avgEntryPrice").asText());
        assertThat(avgEntry).as("avgEntryPrice within CSV bid/ask span ± slippage")
                .isBetween(new BigDecimal("178.40"), new BigDecimal("178.65"));

        assertThat(position.has("unrealizedPnl")).isTrue();
        assertThat(position.has("lastMarkPrice")).isTrue();
        assertThat(new BigDecimal(position.get("realizedPnl").asText())).isEqualByComparingTo("0");

        var portfolio = httpGetAndCheck("/api/portfolio/summary");
        assertThat(portfolio.get("openPositions").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(portfolio.has("totalPnl")).isTrue();

        var exposure = httpGetAndCheck("/api/portfolio/currency-exposure");
        assertThat(exposure.get("rows").isArray()).isTrue();
        var usdRow = findCurrencyRow(exposure, "USD");
        assertThat(usdRow).as("USD exposure row must be present after VWAP fill").isNotNull();
        assertThat(new BigDecimal(usdRow.get("grossExposure").asText()))
                .as("USD gross exposure ≥ 100 shares × ~$178.5 = $17.8k after VWAP fill")
                .isGreaterThanOrEqualTo(new BigDecimal("17000"));

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
        assertThat(quote.get("breakdown").get("advShares").asLong()).isEqualTo(60_000_000L);

        var acceptBody = MAPPER.writeValueAsString(Map.of(
                "quoteId", quote.get("quoteId").asText(),
                "side", "BUY",
                "price", quote.get("ask").asText()));
        var resp = httpPostAndCheck("/api/rfq/accept", acceptBody);
        assertThat(resp.get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void eodReconciliationProducesCleanRunInMirrorMode() throws Exception {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> {
                    var orders = httpGetAndCheck("/api/orders?status=FILLED");
                    return orders.isArray() && orders.size() > 0;
                });

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

        var summary = httpGetAndCheck("/api/recon/summary?date=" + today);
        assertThat(summary.get("totalBreaks").asInt()).isZero();
        assertThat(summary.get("run").get("status").asText()).isEqualTo("SUCCESS");

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
        httpPutAndCheck("/api/strategies/MOMENTUM/parameters", params);
    }

    private void bindCloseToNvdaWith45SharesMocOnly() throws Exception {
        httpPutAndCheck("/api/strategies/NVDA", "{\"strategyName\":\"CLOSE\"}");
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "targetQuantity", 45,
                    "side", "BUY",
                    "windowStart", "10:30:00",
                    "closeTime", "10:31:00",
                    "mocOffsetMinutes", 1,
                    "preCloseFraction", 0.0,
                    "numPreCloseSlices", 6));
        httpPutAndCheck("/api/strategies/CLOSE/parameters", params);
    }

    private void bindPovToTslaWith60Shares() throws Exception {
        httpPutAndCheck("/api/strategies/TSLA", "{\"strategyName\":\"POV\"}");
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "targetQuantity", 60,
                    "side", "BUY",
                    "startTime", "10:30:00",
                    "endTime", "10:31:00",
                    "participationRate", 0.20,
                    "minClipSize", 5,
                    "maxClipSize", 1_000_000));
        httpPutAndCheck("/api/strategies/POV/parameters", params);
    }

    private void bindImplementationShortfallToAmznWith40Shares() throws Exception {
        httpPutAndCheck("/api/strategies/AMZN", "{\"strategyName\":\"IS\"}");
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "targetQuantity", 40,
                    "side", "BUY",
                    "startTime", "10:30:00",
                    "endTime", "10:31:00",
                    "numSlices", 1,
                    "urgency", 0.5));
        httpPutAndCheck("/api/strategies/IS/parameters", params);
    }

    private void bindTwapToMsftWith50Shares() throws Exception {
        httpPutAndCheck("/api/strategies/MSFT", "{\"strategyName\":\"TWAP\"}");
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "targetQuantity", 50,
                    "side", "BUY",
                    "startTime", "10:30:00",
                    "endTime", "10:31:00",
                    "numSlices", 1));
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
        httpPutAndCheck("/api/strategies/VWAP/parameters", params);
    }

    private static BigDecimal netQuantity(JsonNode position){
        return new BigDecimal(position.get("netQuantity").asText());
    }

    private static JsonNode findCurrencyRow(JsonNode exposure, String currency) {
        for (var row : exposure.get("rows")) {
            if (currency.equals(row.get("currency").asText())) {
                return row;
            }
        }
        return null;
    }

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
            return null;
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