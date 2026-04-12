package com.mariaalpha.strategyengine.ml;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.proto.signal.Direction;
import com.mariaalpha.proto.signal.SignalRequest;
import com.mariaalpha.proto.signal.SignalResponse;
import com.mariaalpha.proto.signal.SignalServiceGrpc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MlSignalClientTest {

  private ManagedChannel channel;
  private MlSignalClient client;
  private FakeSignalService fakeSignalService;
  private Server server;

  @BeforeEach
  void setUp() throws IOException {
    fakeSignalService = new FakeSignalService();
    var serverName = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(fakeSignalService)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    client = new MlSignalClient(channel, new SimpleMeterRegistry());
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    client.shutDown();
    server.shutdown().awaitTermination();
  }

  @Test
  void getSignalReturnsResultWhenServiceAvailable() {
    fakeSignalService.nextResponse(
        SignalResponse.newBuilder()
            .setSymbol("AAPL")
            .setDirection(Direction.LONG)
            .setConfidence(0.85)
            .build());

    var result = client.getSignal("AAPL");
    assertThat(result).isPresent();
    assertThat(result.get().direction()).isEqualTo(Direction.LONG);
    assertThat(result.get().confidence()).isEqualTo(0.85);
  }

  @Test
  void getSignalReturnsEmptyWhenServiceErrors() {
    fakeSignalService.nextError(Status.UNAVAILABLE.asRuntimeException());
    var result = client.getSignal("AAPL");
    assertThat(result).isEmpty();
  }

  @Test
  void circuitBreakerOpensAfterSlidingWindowOfFailures() {
    fakeSignalService.nextError(Status.UNAVAILABLE.asRuntimeException());
    for (int i = 0; i < 5; i++) {
      client.getSignal("AAPL");
      // refresh the error each call (FakeSignalService clears after use)
      fakeSignalService.nextError(Status.UNAVAILABLE.asRuntimeException());
    }
    assertThat(client.getCircuitBreakerState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  static class FakeSignalService extends SignalServiceGrpc.SignalServiceImplBase {
    private SignalResponse response;
    private StatusRuntimeException error;

    void nextResponse(SignalResponse r) {
      this.response = r;
      this.error = null;
    }

    void nextError(StatusRuntimeException e) {
      this.error = e;
      this.response = null;
    }

    @Override
    public void getSignal(SignalRequest request, StreamObserver<SignalResponse> observer) {
      if (error != null) {
        observer.onError(error);
      } else {
        observer.onNext(response);
        observer.onCompleted();
      }
    }
  }
}
