package com.mariaalpha.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatedHappyPathE2ETest {

    private static final String API_KEY = "e2e-test-key";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private ComposeContainer composeContainer;
    private HttpClient httpClient;
    private String baseUrl;

    @AfterAll
    void stopStack(){
        if(composeContainer != null){
            composeContainer.stop();
        }
    }

    @BeforeAll
    void startStack() throws Exception {
        var dockerComposeFile = new File("../docker-compose.yml");
        // Force-down any stale stack left by a previous crashed/killed run before starting fresh.
        new ProcessBuilder("docker", "compose", "-f", dockerComposeFile.getCanonicalPath(), "down", "-v", "--remove-orphans")
                .directory(dockerComposeFile.getCanonicalFile().getParentFile())
                .redirectErrorStream(true)
                .start()
                .waitFor();
        composeContainer = new ComposeContainer(dockerComposeFile)
                .withLocalCompose(true)
                .withEnv("MARIAALPHA_API_KEY", API_KEY)
                .withEnv("POSTGRES_USER","mariaalpha")
                .withEnv("POSTGRES_PASSWORD","mariaalpha")
                .withEnv("POSTGRES_DB","mariaalpha")
                // Alpaca creds aren't used by simulated profile but compose interpolates them.
                .withEnv("ALPACA_API_KEY_ID","unused")
                .withEnv("ALPACA_API_SECRET_KEY","unused")
                .withBuild(true)
                .withRemoveVolumes(true)
                // withExposedService routes through a socat ambassador that can't cross
                // Docker user-defined networks (compose v2). Use log-message wait instead:
                // Docker log API streams directly from the container regardless of network.
                .waitingFor("api-gateway",
                        Wait.forLogMessage(".*Started Application in.*", 1)
                                .withStartupTimeout(Duration.ofMinutes(4)))
                .withLogConsumer("api-gateway", f -> System.out.print("[api-gw] " + f.getUtf8String()))
                .withLogConsumer("strategy-engine", f -> System.out.print("[strategy] " + f.getUtf8String()))
                .withLogConsumer("execution-engine", f -> System.out.print("[exec] " + f.getUtf8String()))
                .withLogConsumer("order-manager", f -> System.out.print("[om] " + f.getUtf8String()));
        composeContainer.start();
        // LocalDockerCompose binds compose ports directly on the host (8080:8080 in docker-compose.yml).
        baseUrl = "http://localhost:8080";
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void simulatedTickReachesPositionWithFillAndPnl() throws Exception {
        bindVwapToAppleWith100Shares();

        var position = await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> getPosition("AAPL"), pos -> pos != null && netQuantity(pos).signum() != 0);

        assertThat(netQuantity(position)).as("AAPL net quantity should be 100 after VWAP bin fires")
                .isEqualByComparingTo(new BigDecimal("100"));

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

        var orders = httpGetAndCheck("/api/orders?symbol=AAPL");
        assertThat(orders.isArray()).isTrue();
        assertThat(orders.size()).isGreaterThanOrEqualTo(1);
        var order = orders.get(0);
        assertThat(order.get("status").asText()).isEqualTo("FILLED");
        assertThat(order.get("strategy").asText()).isEqualTo("VWAP");

        var status = httpGetAndCheck("/api/execution/status");
        assertThat(status.get("tradingHalted").asBoolean())
                .as("daily loss limit should not have tripped on a single 100-share fill")
                .isFalse();
    }

    @Test
    void simulatedTwapTickReachesMsftPositionWithFill() throws Exception {
        bindTwapToMsftWith50Shares();

        var position = await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> getPosition("MSFT"), pos -> pos != null && netQuantity(pos).signum() != 0);

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
    void simulatedMomentumUptrendReachesGooglOrderWithFill() throws Exception {
        bindMomentumToGoogl();

        // As the simulated GOOGL uptrend's fast EMA crosses above the slow EMA, Momentum opens a
        // long position. A later loop's reverse-crossover may flatten it, so we gate on the
        // persisted (append-only) FILLED BUY order rather than a live, possibly-oscillating net
        // position.
        var order = await().atMost(40, TimeUnit.SECONDS)
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
        var params = MAPPER.writeValueAsString(
                Map.of(
                    "fastPeriod", 2,
                    "slowPeriod", 3,
                    "warmupTrades", 2,
                    "rsiPeriod", 14,
                    "volumeMultiplier", 1.5,
                    "tradeQuantity", 30,
                    "side", "BUY",
                    "stopLossPct", 0.0));
        // Parameters are addressed by strategy name, not symbol
        httpPutAndCheck("/api/strategies/MOMENTUM/parameters", params);
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

    private JsonNode getPosition(String symbol) throws Exception {
        var requestPath = "/api/positions/" + symbol;
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + requestPath))
                        .header("X-API-Key", API_KEY)
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
                        .header("X-API-Key", API_KEY)
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
                        .header("X-API-Key", API_KEY)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("PUT %s → %s", requestPath, response.body()).isBetween(200, 204);
    }

}