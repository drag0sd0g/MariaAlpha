package com.mariaalpha.marketdatagateway.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.marketdatagateway.book.OrderBookEntry;
import com.mariaalpha.marketdatagateway.book.OrderBookManager;
import com.mariaalpha.proto.marketdata.BookSnapshotRequest;
import com.mariaalpha.proto.marketdata.BookSnapshotResponse;
import com.mariaalpha.proto.marketdata.MarketDataServiceGrpc;
import com.mariaalpha.proto.marketdata.StreamBookSnapshotsRequest;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class BookGrpcServiceTest {

  private Server server;
  private ManagedChannel channel;
  private MarketDataServiceGrpc.MarketDataServiceBlockingStub blockingStub;
  private OrderBookManager orderBookManager;

  @BeforeEach
  void setUp() throws Exception {
    orderBookManager = mock(OrderBookManager.class);

    var service = new BookGrpcService(orderBookManager);
    String serverName = InProcessServerBuilder.generateName();

    server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(service)
            .build()
            .start();

    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    blockingStub = MarketDataServiceGrpc.newBlockingStub(channel);
  }

  @AfterEach
  void tearDown() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void getBookSnapshotReturnsCurrentState() {
    var entry =
        new OrderBookEntry(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            200L,
            150L,
            new BigDecimal("178.52"),
            12345678L,
            Instant.parse("2026-03-24T14:30:00.123Z"));
    when(orderBookManager.getSnapshot("AAPL")).thenReturn(entry);

    var response =
        blockingStub.getBookSnapshot(BookSnapshotRequest.newBuilder().setSymbol("AAPL").build());

    assertThat(response.getSymbol()).isEqualTo("AAPL");
    assertThat(response.getBidPrice()).isEqualTo(178.50);
    assertThat(response.getAskPrice()).isEqualTo(178.54);
    assertThat(response.getBidSize()).isEqualTo(200L);
    assertThat(response.getAskSize()).isEqualTo(150L);
    assertThat(response.getLastPrice()).isEqualTo(178.52);
    assertThat(response.getCumulativeVolume()).isEqualTo(12345678L);
  }

  @Test
  void getBookSnapshotForUnknownSymbolReturnsDefaults() {
    when(orderBookManager.getSnapshot("UNKNOWN")).thenReturn(OrderBookEntry.empty("UNKNOWN"));

    var response =
        blockingStub.getBookSnapshot(BookSnapshotRequest.newBuilder().setSymbol("UNKNOWN").build());

    assertThat(response.getSymbol()).isEqualTo("UNKNOWN");
    assertThat(response.getBidPrice()).isEqualTo(0.0);
    assertThat(response.getLastPrice()).isEqualTo(0.0);
    assertThat(response.getCumulativeVolume()).isZero();
  }

  @Test
  void streamBookSnapshotsEmitsUpdates() {
    var entry1 =
        new OrderBookEntry(
            "AAPL",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0L,
            0L,
            new BigDecimal("178.52"),
            1000000L,
            Instant.parse("2026-03-24T14:30:00Z"));
    var entry2 =
        new OrderBookEntry(
            "AAPL",
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            200L,
            150L,
            new BigDecimal("178.52"),
            1000000L,
            Instant.parse("2026-03-24T14:30:01Z"));

    when(orderBookManager.streamSnapshots(any())).thenReturn(Flux.just(entry1, entry2));

    var request = StreamBookSnapshotsRequest.newBuilder().addSymbols("AAPL").build();
    Iterator<BookSnapshotResponse> responses = blockingStub.streamBookSnapshots(request);

    var results = new ArrayList<BookSnapshotResponse>();
    responses.forEachRemaining(results::add);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).getLastPrice()).isEqualTo(178.52);
    assertThat(results.get(1).getBidPrice()).isEqualTo(178.50);
    assertThat(results.get(1).getAskPrice()).isEqualTo(178.54);
  }

  @Test
  void toProtoConvertsAllFields() {
    var entry =
        new OrderBookEntry(
            "MSFT",
            new BigDecimal("415.18"),
            new BigDecimal("415.24"),
            100L,
            80L,
            new BigDecimal("415.20"),
            500000L,
            Instant.parse("2026-03-24T14:30:00.456Z"));

    var proto = BookGrpcService.toProto(entry);

    assertThat(proto.getSymbol()).isEqualTo("MSFT");
    assertThat(proto.getBidPrice()).isEqualTo(415.18);
    assertThat(proto.getAskPrice()).isEqualTo(415.24);
    assertThat(proto.getBidSize()).isEqualTo(100L);
    assertThat(proto.getAskSize()).isEqualTo(80L);
    assertThat(proto.getLastPrice()).isEqualTo(415.20);
    assertThat(proto.getCumulativeVolume()).isEqualTo(500000L);
    assertThat(proto.getTimestamp().getSeconds())
        .isEqualTo(Instant.parse("2026-03-24T14:30:00.456Z").getEpochSecond());
    assertThat(proto.getTimestamp().getNanos())
        .isEqualTo(Instant.parse("2026-03-24T14:30:00.456Z").getNano());
  }
}
