package com.mariaalpha.apigateway.fix;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.UnsupportedMessageType;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReject;
import quickfix.fix44.OrderCancelRequest;

@Component
public class FixApplication extends quickfix.fix44.MessageCracker implements quickfix.Application {

  private static final Logger LOG = LoggerFactory.getLogger(FixApplication.class);

  private static final char EXEC_NEW = '0';
  private static final char EXEC_CANCELED = '4';
  private static final char EXEC_REJECTED = '8';
  private static final char ORD_NEW = '0';
  private static final char ORD_CANCELED = '4';
  private static final char ORD_REJECTED = '8';
  private static final char CXL_REJ_RESPONSE_TO_CANCEL = '1';
  private static final String NO_ORDER_ID = "NONE";
  private static final String NO_SYMBOL = "UNKNOWN";

  private final FixGatewayClient client;
  private final FixGatewayMetrics metrics;
  private final Map<String, String> clOrdToDownstream = new ConcurrentHashMap<>();

  public FixApplication(FixGatewayClient client, FixGatewayMetrics metrics) {
    this.client = client;
    this.metrics = metrics;
  }

  @Override
  public void onCreate(SessionID sessionId) {
    LOG.info("FIX session created: {}", sessionId);
  }

  @Override
  public void onLogon(SessionID sessionId) {
    LOG.info("FIX client logged on: {}", sessionId);
  }

  @Override
  public void onLogout(SessionID sessionId) {
    LOG.info("FIX client logged out: {}", sessionId);
  }

  @Override
  public void toAdmin(Message message, SessionID sessionId) {}

  @Override
  public void fromAdmin(Message message, SessionID sessionId) {}

  @Override
  public void toApp(Message message, SessionID sessionId) {}

  @Override
  public void fromApp(Message message, SessionID sessionId)
      throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
    crack(message, sessionId);
  }

  @Override
  public void onMessage(NewOrderSingle message, SessionID sessionId) {
    send(handleNewOrderSingle(message, sessionId), sessionId);
  }

  @Override
  public void onMessage(OrderCancelRequest message, SessionID sessionId) {
    send(handleOrderCancelRequest(message, sessionId), sessionId);
  }

  public Message handleNewOrderSingle(NewOrderSingle nos, SessionID sessionId) {
    String clOrdId = "UNKNOWN";
    String symbol = NO_SYMBOL;
    char sideChar = '1';
    try {
      clOrdId = nos.getClOrdID().getValue();
      sideChar = nos.getSide().getValue();
      if (nos.isSetSymbol()) {
        symbol = nos.getSymbol().getValue();
      }
      var submission = FixOrderTranslator.translate(nos);
      symbol = submission.symbol();
      var result = client.submitOrder(submission);
      if (!result.accepted()) {
        metrics.recordRejected();
        return executionReport(
            clOrdId,
            NO_ORDER_ID,
            symbol,
            sideChar,
            submission.quantity(),
            EXEC_REJECTED,
            ORD_REJECTED,
            result.reason());
      }
      clOrdToDownstream.put(clOrdId, result.downstreamOrderId());
      metrics.recordAccepted();
      return executionReport(
          clOrdId,
          result.downstreamOrderId(),
          symbol,
          sideChar,
          submission.quantity(),
          EXEC_NEW,
          ORD_NEW,
          null);
    } catch (FieldNotFound e) {
      metrics.recordRejected();
      return executionReport(
          clOrdId,
          NO_ORDER_ID,
          symbol,
          sideChar,
          0,
          EXEC_REJECTED,
          ORD_REJECTED,
          "missing required field: " + e.field);
    } catch (IllegalArgumentException e) {
      metrics.recordRejected();
      return executionReport(
          clOrdId, NO_ORDER_ID, symbol, sideChar, 0, EXEC_REJECTED, ORD_REJECTED, e.getMessage());
    }
  }

  public Message handleOrderCancelRequest(OrderCancelRequest ocr, SessionID sessionId) {
    String clOrdId = "UNKNOWN";
    String origClOrdId = "UNKNOWN";
    String symbol = NO_SYMBOL;
    char sideChar = '1';
    try {
      clOrdId = ocr.getClOrdID().getValue();
      origClOrdId = ocr.getOrigClOrdID().getValue();
      if (ocr.isSetSide()) {
        sideChar = ocr.getSide().getValue();
      }
      if (ocr.isSetSymbol()) {
        symbol = ocr.getSymbol().getValue();
      }
      var downstreamId = clOrdToDownstream.get(origClOrdId);
      if (downstreamId == null) {
        return orderCancelReject(clOrdId, origClOrdId, "unknown OrigClOrdID");
      }
      var result = client.cancelOrder(downstreamId);
      if (!result.accepted()) {
        return orderCancelReject(clOrdId, origClOrdId, result.reason());
      }
      clOrdToDownstream.remove(origClOrdId);
      metrics.recordCancelled();
      var er =
          executionReport(
              clOrdId, downstreamId, symbol, sideChar, 0, EXEC_CANCELED, ORD_CANCELED, null);
      ((ExecutionReport) er).set(new OrigClOrdID(origClOrdId));
      return er;
    } catch (FieldNotFound e) {
      return orderCancelReject(clOrdId, origClOrdId, "missing required field: " + e.field);
    }
  }

  private Message executionReport(
      String clOrdId,
      String orderId,
      String symbol,
      char sideChar,
      int quantity,
      char execType,
      char ordStatus,
      String reason) {
    var report =
        new ExecutionReport(
            new OrderID(orderId),
            new ExecID(UUID.randomUUID().toString()),
            new ExecType(execType),
            new OrdStatus(ordStatus),
            new Side(sideChar),
            new LeavesQty(execType == EXEC_NEW ? quantity : 0),
            new CumQty(0),
            new AvgPx(0));
    report.set(new Symbol(symbol));
    report.set(new ClOrdID(clOrdId));
    report.set(new OrderQty(quantity));
    report.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
    if (reason != null) {
      report.set(new Text(reason));
    }
    return report;
  }

  private Message orderCancelReject(String clOrdId, String origClOrdId, String reason) {
    var reject =
        new OrderCancelReject(
            new OrderID(NO_ORDER_ID),
            new ClOrdID(clOrdId),
            new OrigClOrdID(origClOrdId),
            new OrdStatus(ORD_REJECTED),
            new CxlRejResponseTo(CXL_REJ_RESPONSE_TO_CANCEL));
    reject.set(new Text(reason));
    return reject;
  }

  private void send(Message message, SessionID sessionId) {
    try {
      Session.sendToTarget(message, sessionId);
    } catch (SessionNotFound e) {
      LOG.warn("Cannot send FIX response — session not found: {}", sessionId);
    }
  }
}
