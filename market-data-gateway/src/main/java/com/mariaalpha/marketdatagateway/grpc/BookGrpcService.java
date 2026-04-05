package com.mariaalpha.marketdatagateway.grpc;

import com.google.protobuf.Timestamp;
import com.mariaalpha.marketdatagateway.book.OrderBookEntry;
import com.mariaalpha.marketdatagateway.book.OrderBookManager;
import com.mariaalpha.proto.marketdata.BookSnapshotRequest;
import com.mariaalpha.proto.marketdata.BookSnapshotResponse;
import com.mariaalpha.proto.marketdata.MarketDataServiceGrpc;
import com.mariaalpha.proto.marketdata.StreamBookSnapshotsRequest;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.HashSet;
import net.devh.boot.grpc.server.service.GrpcService;
import reactor.core.Disposable;

@GrpcService
public class BookGrpcService extends MarketDataServiceGrpc.MarketDataServiceImplBase {

  private final OrderBookManager orderBookManager;

  public BookGrpcService(OrderBookManager orderBookManager) {
    this.orderBookManager = orderBookManager;
  }

  @Override
  public void getBookSnapshot(
      BookSnapshotRequest request, StreamObserver<BookSnapshotResponse> responseObserver) {
    var entry = orderBookManager.getSnapshot(request.getSymbol());
    responseObserver.onNext(toProto(entry));
    responseObserver.onCompleted();
  }

  @Override
  public void streamBookSnapshots(
      StreamBookSnapshotsRequest request, StreamObserver<BookSnapshotResponse> responseObserver) {
    var symbols = new HashSet<>(request.getSymbolsList());
    var serverObserver = (ServerCallStreamObserver<BookSnapshotResponse>) responseObserver;

    Disposable subscription =
        orderBookManager
            .streamSnapshots(symbols)
            .subscribe(
                entry -> {
                  synchronized (responseObserver) {
                    if (!serverObserver.isCancelled()) {
                      responseObserver.onNext(toProto(entry));
                    }
                  }
                },
                responseObserver::onError,
                responseObserver::onCompleted);

    serverObserver.setOnCancelHandler(subscription::dispose);
  }

  static BookSnapshotResponse toProto(OrderBookEntry entry) {
    var builder =
        BookSnapshotResponse.newBuilder()
            .setSymbol(entry.symbol())
            .setBidPrice(entry.bidPrice().doubleValue())
            .setAskPrice(entry.askPrice().doubleValue())
            .setBidSize(entry.bidSize())
            .setAskSize(entry.askSize())
            .setLastPrice(entry.lastPrice().doubleValue())
            .setCumulativeVolume(entry.cumulativeVolume());

    if (entry.lastUpdated() != null) {
      builder.setTimestamp(
          Timestamp.newBuilder()
              .setSeconds(entry.lastUpdated().getEpochSecond())
              .setNanos(entry.lastUpdated().getNano())
              .build());
    }

    return builder.build();
  }
}
