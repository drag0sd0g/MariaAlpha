package com.mariaalpha.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BasketTradingE2ETest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private final String apiKey = SharedComposeStack.get().apiKey();
  private final String baseUrl = SharedComposeStack.get().gatewayBaseUrl();
  private HttpClient httpClient;

  @BeforeAll
  void startStack() {
    SharedComposeStack.get().start();
    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Test
  void submitMarketBasketThroughGatewayReachesFilled() throws Exception {
    var basket =
        await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(Duration.ofSeconds(2))
            .ignoreExceptions()
            .until(this::submitFullyAcceptedBasket, b -> b != null);

    var basketId = basket.get("basketId").asText();
    assertThat(basket.get("totalLegs").asInt()).isEqualTo(2);
    assertThat(basket.get("acceptedLegs").asInt()).isEqualTo(2);

    var filled =
        await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(500))
            .ignoreExceptions()
            .until(() -> getBasket(basketId), b -> "FILLED".equals(b.get("status").asText()));

    assertThat(filled.get("filledLegs").asInt()).isEqualTo(2);
    assertThat(filled.get("filledQuantity").asInt()).isEqualTo(5);

    var list = httpGet("/api/execution/baskets", 200);
    boolean found = false;
    for (var entry : list) {
      if (basketId.equals(entry.get("basketId").asText())) {
        found = true;
        break;
      }
    }
    assertThat(found).as("listed baskets must include the just-submitted one").isTrue();
  }

  @Test
  void emptyBasketIsRejected() throws Exception {
    var resp = postRaw("/api/execution/baskets", "{\"name\":\"empty\",\"legs\":[]}");
    assertThat(resp.statusCode()).as("empty basket → %s", resp.body()).isEqualTo(400);
  }

  @Test
  void limitLegWithoutPriceIsRejected() throws Exception {
    var body =
        "{\"name\":\"bad\",\"legs\":[{\"symbol\":\"AAPL\",\"side\":\"BUY\","
            + "\"orderType\":\"LIMIT\",\"quantity\":10}]}";
    var resp = postRaw("/api/execution/baskets", body);
    assertThat(resp.statusCode()).as("LIMIT leg without price → %s", resp.body()).isEqualTo(400);
  }

  private JsonNode submitFullyAcceptedBasket() throws Exception {
    var body =
        "{\"name\":\"e2e-rebalance\",\"legs\":["
            + "{\"symbol\":\"GOOGL\",\"side\":\"BUY\",\"orderType\":\"MARKET\",\"quantity\":3},"
            + "{\"symbol\":\"AMZN\",\"side\":\"BUY\",\"orderType\":\"MARKET\",\"quantity\":2}"
            + "]}";
    var resp = postRaw("/api/execution/baskets", body);
    if (resp.statusCode() != 202) {
      return null;
    }
    var view = MAPPER.readTree(resp.body());
    return view.get("acceptedLegs").asInt() == view.get("totalLegs").asInt() ? view : null;
  }

  private JsonNode getBasket(String basketId) throws Exception {
    return httpGet("/api/execution/baskets/" + basketId, 200);
  }

  private JsonNode httpGet(String path, int expectedStatus) throws Exception {
    var response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("GET %s → %d / body=%s", path, response.statusCode(), response.body())
        .isEqualTo(expectedStatus);
    return MAPPER.readTree(response.body());
  }

  private HttpResponse<String> postRaw(String path, String body) throws Exception {
    return httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("X-API-Key", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(5))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
