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

/**
 * E2E coverage for the Random Forest regime classifier in ml-signal-service.
 *
 * <p>Brings up the bare minimum compose profile needed to exercise the GetRegime gRPC: just the
 * ml-signal-service container (no Kafka dependency for the call itself — we only need the gRPC
 * surface). The service is verified to:
 *
 * <ul>
 *   <li>load the regime model on startup (reported via the readiness probe), and
 *   <li>return a well-formed RegimeResponse on demand — even when no bar history is present, the
 *       service must degrade to UNKNOWN/confidence=0 rather than fail the RPC. This is the
 *       contract the strategy engine relies on when ML is acting purely advisory.
 * </ul>
 *
 * <p>Accuracy of the classifier on synthetic regime paths is covered by the Python integration
 * test {@code tests/test_regime_integration.py}; this Java e2e only validates the deployment +
 * wire contract.
 */
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
    // The shared compose stack is owned by SharedComposeStack and stopped at JVM shutdown.
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
              // The readiness body advertises the regime model state alongside the signal model.
              assertThat(resp.body()).contains("\"regime_model_loaded\":true");
            });
  }

  @Test
  void getRegimeReturnsUnknownWhenNoBarsAvailable() {
    // Before any ticks have flowed (or for a never-seen symbol), the service must answer
    // UNKNOWN/confidence=0 rather than throw — this is the strategy engine's safety net.
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
    // After the market-data-gateway pushes ticks for AAPL through Kafka, the ml-signal-service
    // will eventually have a full bar window. We don't gate on that here (the test would take
    // ~60 minutes of simulated time), but we do require the response to be a valid enum value
    // — i.e. the gRPC path itself round-trips a parseable MarketRegime.
    var response =
        stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .getRegime(RegimeRequest.newBuilder().setSymbol("AAPL").build());
    assertThat(response.getSymbol()).isEqualTo("AAPL");
    assertThat(EnumSet.allOf(MarketRegime.class)).contains(response.getRegime());
    assertThat(response.getConfidence()).isBetween(0.0, 1.0);
  }

  @Test
  void metricsExposeRegimeCounter() throws Exception {
    // Trigger at least one GetRegime call to materialise the counter, then scrape /metrics.
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
