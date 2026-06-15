package com.mariaalpha.apigateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quickfix.SessionID;
import quickfix.field.ClOrdID;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReject;
import quickfix.fix44.OrderCancelRequest;

class FixApplicationTest {

  private static final SessionID SESSION = new SessionID("FIX.4.4", "MARIAALPHA", "CLIENT");

  private FixGatewayClient client;
  private FixApplication app;

  @BeforeEach
  void setUp() {
    client = mock(FixGatewayClient.class);
    app = new FixApplication(client, new FixGatewayMetrics(new SimpleMeterRegistry()));
  }

  private NewOrderSingle limitOrder(String clOrdId) {
    var nos =
        new NewOrderSingle(
            new ClOrdID(clOrdId),
            new Side('1'),
            new TransactTime(LocalDateTime.now(ZoneOffset.UTC)),
            new OrdType('2'));
    nos.set(new Symbol("AAPL"));
    nos.set(new OrderQty(100));
    nos.set(new Price(150.00));
    return nos;
  }

  private OrderCancelRequest cancel(String origClOrdId, String clOrdId) {
    var ocr =
        new OrderCancelRequest(
            new OrigClOrdID(origClOrdId),
            new ClOrdID(clOrdId),
            new Side('1'),
            new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
    ocr.set(new Symbol("AAPL"));
    return ocr;
  }

  @Test
  void newOrderAccepted_returnsNewExecutionReport() throws Exception {
    when(client.submitOrder(any())).thenReturn(FixDownstreamResult.accepted("EXEC-1"));

    var report = (ExecutionReport) app.handleNewOrderSingle(limitOrder("c1"), SESSION);

    assertThat(report.getExecType().getValue()).isEqualTo('0');
    assertThat(report.getOrdStatus().getValue()).isEqualTo('0');
    assertThat(report.getOrderID().getValue()).isEqualTo("EXEC-1");
    assertThat(report.getClOrdID().getValue()).isEqualTo("c1");
    // Symbol (tag 55) is required on a FIX 4.4 ExecutionReport — without it a strict client
    // rejects the ack and never sees the order acknowledged.
    assertThat(report.getSymbol().getValue()).isEqualTo("AAPL");
  }

  @Test
  void newOrderRejectedDownstream_returnsRejectedExecutionReport() throws Exception {
    when(client.submitOrder(any()))
        .thenReturn(FixDownstreamResult.rejected("rejected by execution-engine"));

    var report = (ExecutionReport) app.handleNewOrderSingle(limitOrder("c2"), SESSION);

    assertThat(report.getExecType().getValue()).isEqualTo('8');
    assertThat(report.getText().getValue()).contains("execution-engine");
  }

  @Test
  void newOrderMissingSymbol_rejectsWithoutCallingDownstream() throws Exception {
    var nos =
        new NewOrderSingle(
            new ClOrdID("c3"),
            new Side('1'),
            new TransactTime(LocalDateTime.now(ZoneOffset.UTC)),
            new OrdType('1'));
    nos.set(new OrderQty(100)); // no Symbol → FieldNotFound during translate

    var report = (ExecutionReport) app.handleNewOrderSingle(nos, SESSION);

    assertThat(report.getExecType().getValue()).isEqualTo('8');
    verify(client, never()).submitOrder(any());
  }

  @Test
  void cancelKnownOrder_returnsCanceledExecutionReport() throws Exception {
    when(client.submitOrder(any())).thenReturn(FixDownstreamResult.accepted("EXEC-9"));
    when(client.cancelOrder("EXEC-9")).thenReturn(FixDownstreamResult.accepted("EXEC-9"));
    app.handleNewOrderSingle(limitOrder("c9"), SESSION); // registers c9 → EXEC-9

    var report = (ExecutionReport) app.handleOrderCancelRequest(cancel("c9", "cxl9"), SESSION);

    assertThat(report.getExecType().getValue()).isEqualTo('4');
    assertThat(report.getOrigClOrdID().getValue()).isEqualTo("c9");
    assertThat(report.getSymbol().getValue()).isEqualTo("AAPL");
  }

  @Test
  void cancelUnknownOrder_returnsOrderCancelReject() throws Exception {
    var reject = (OrderCancelReject) app.handleOrderCancelRequest(cancel("ghost", "cxl0"), SESSION);

    assertThat(reject.getText().getValue()).contains("unknown");
    verify(client, never()).cancelOrder(any());
  }
}
