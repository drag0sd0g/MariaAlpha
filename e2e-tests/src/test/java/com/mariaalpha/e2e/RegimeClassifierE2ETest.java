package com.mariaalpha.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mariaalpha.proto.signal.MarketRegime;
import com.mariaalpha.proto.signal.RegimeRequest;
import com.mariaalpha.proto.signal.SignalServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegimeClassifierE2ETest {

  private static final int GRPC_PORT = 50051;
  private static final int API_PORT = 8090;

  private HttpClient httpClient;
  private ManagedChannel channel;
  private SignalServiceGrpc.SignalServiceBlockingStub stub;

  @AfterAll
  void closeChannel() {
    if (channel != null) {
      channel.shutdownNow();
    }
  }

  @BeforeAll
  void startStack() {
    SharedComposeStack.get().start();
    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    channel = ManagedChannelBuilder.forAddress("localhost", GRPC_PORT).usePlaintext().build();
    stub = SignalServiceGrpc.newBlockingStub(channel);
  }

  @Test
  void readinessReportsRegimeModelLoaded() {
    await()
        .atMost(60, TimeUnit.SECONDS)
        .pollInterval(Duration.ofSeconds(1))
        .ignoreExceptions()
        .untilAsserted(
            () -> {
              var resp =
                  httpClient.send(
                      HttpRequest.newBuilder()
                          .uri(URI.create("http://localhost:" + API_PORT + "/ready"))
                          .GET()
                          .timeout(Duration.ofSeconds(3))
                          .build(),
                      HttpResponse.BodyHandlers.ofString());
              assertThat(resp.statusCode()).isEqualTo(200);
              assertThat(resp.body()).contains("\"regime_model_loaded\":true");
            });
  }

  @Test
  void getRegimeReturnsUnknownWhenNoBarsAvailable() {
    var response =
        stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .getRegime(RegimeRequest.newBuilder().setSymbol("ZZZZ_NEVER_TRADED").build());
    assertThat(response.getSymbol()).isEqualTo("ZZZZ_NEVER_TRADED");
    assertThat(response.getRegime()).isEqualTo(MarketRegime.UNKNOWN);
    assertThat(response.getConfidence()).isEqualTo(0.0);
    assertThat(response.getTimestamp().getSeconds()).isGreaterThan(0L);
  }

  @Test
  void getRegimeAcceptsAnyEnumOnRealSymbol() {
    // will eventually have a full bar window. We don't gate on that here (the test would take
    var response =
        stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .getRegime(RegimeRequest.newBuilder().setSymbol("AAPL").build());
    assertThat(response.getSymbol()).isEqualTo("AAPL");
    assertThat(EnumSet.allOf(MarketRegime.class)).contains(response.getRegime());
    assertThat(response.getConfidence()).isBetween(0.0, 1.0);
  }

  @Test
  void metricsExposeRegimeCounter() throws Exception {
    stub.withDeadlineAfter(2, TimeUnit.SECONDS)
        .getRegime(RegimeRequest.newBuilder().setSymbol("METRIC_PROBE").build());

    var resp =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_PORT + "/metrics"))
                .GET()
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body())
        .as("regime prediction counter and grpc request counter must be exposed")
        .contains("mariaalpha_ml_regime_predictions_total")
        .contains("mariaalpha_ml_grpc_requests_total");
  }
}
