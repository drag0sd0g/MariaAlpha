package com.mariaalpha.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.Initiator;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
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
import quickfix.fix44.OrderCancelRequest;

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FixGatewayE2ETest {

  private static final SessionID SESSION = new SessionID("FIX.4.4", "CLIENT", "MARIAALPHA");

  private final CapturingApplication app = new CapturingApplication();
  private Initiator initiator;

  @BeforeAll
  void startStackAndInitiator() throws Exception {
    SharedComposeStack.get().start();

    var settings = new SessionSettings();
    settings.setString("ConnectionType", "initiator");
    settings.setString("StartTime", "00:00:00");
    settings.setString("EndTime", "00:00:00");
    settings.setString(SESSION, "ConnectionType", "initiator");
    settings.setString(SESSION, "BeginString", "FIX.4.4");
    settings.setString(SESSION, "SenderCompID", "CLIENT");
    settings.setString(SESSION, "TargetCompID", "MARIAALPHA");
    settings.setString(SESSION, "SocketConnectHost", "localhost");
    settings.setLong(SESSION, "SocketConnectPort", 9878);
    settings.setLong(SESSION, "HeartBtInt", 10);
    settings.setLong(SESSION, "ReconnectInterval", 1);
    settings.setString(SESSION, "StartTime", "00:00:00");
    settings.setString(SESSION, "EndTime", "00:00:00");
    settings.setString(SESSION, "UseDataDictionary", "Y");
    settings.setString(SESSION, "DataDictionary", "FIX44.xml");

    initiator =
        new SocketInitiator(
            app,
            new MemoryStoreFactory(),
            settings,
            new SLF4JLogFactory(settings),
            new DefaultMessageFactory());
    initiator.start();

    await()
        .atMost(45, TimeUnit.SECONDS)
        .pollInterval(java.time.Duration.ofSeconds(1))
        .until(app.loggedOn::get);
  }

  @AfterAll
  void stopInitiator() {
    if (initiator != null) {
      initiator.stop();
    }
  }

  @Test
  void newOrderIsAckedThenCancelled() throws Exception {
    var clOrdId = "e2e-fix-" + UUID.randomUUID();

    var nos =
        new NewOrderSingle(
            new ClOrdID(clOrdId),
            new Side('1'), // BUY
            new TransactTime(LocalDateTime.now(ZoneOffset.UTC)),
            new OrdType('2')); // LIMIT
    nos.set(new Symbol("GOOGL"));
    nos.set(new OrderQty(2));
    nos.set(new Price(1.00)); // deep in book → rests, does not cross
    Session.sendToTarget(nos, SESSION);

    var ack =
        await()
            .atMost(40, TimeUnit.SECONDS)
            .pollInterval(java.time.Duration.ofMillis(500))
            .until(() -> app.reportFor(clOrdId, '0'), Optional::isPresent)
            .orElseThrow();
    assertThat(ack.getOrdStatus().getValue()).isEqualTo('0'); // NEW
    assertThat(ack.getSymbol().getValue()).isEqualTo("GOOGL");

    // Cancel the resting order; the gateway resolves OrigClOrdID → the internal order id.
    var cancelClOrdId = clOrdId + "-cxl";
    var cancel =
        new OrderCancelRequest(
            new OrigClOrdID(clOrdId),
            new ClOrdID(cancelClOrdId),
            new Side('1'),
            new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
    cancel.set(new Symbol("GOOGL"));
    Session.sendToTarget(cancel, SESSION);

    var cancelled =
        await()
            .atMost(40, TimeUnit.SECONDS)
            .pollInterval(java.time.Duration.ofMillis(500))
            .until(() -> app.reportFor(cancelClOrdId, '4'), Optional::isPresent)
            .orElseThrow();
    assertThat(cancelled.getOrigClOrdID().getValue()).isEqualTo(clOrdId);
  }

  /** QuickFIX/J client application that records logon state and inbound ExecutionReports. */
  private static final class CapturingApplication implements Application {
    private final AtomicBoolean loggedOn = new AtomicBoolean(false);
    private final List<ExecutionReport> reports = new CopyOnWriteArrayList<>();

    /** First ExecutionReport matching the given ClOrdID and ExecType, if any. */
    Optional<ExecutionReport> reportFor(String clOrdId, char execType) {
      return reports.stream()
          .filter(
              er -> {
                try {
                  return clOrdId.equals(er.getClOrdID().getValue())
                      && er.getExecType().getValue() == execType;
                } catch (Exception e) {
                  return false;
                }
              })
          .findFirst();
    }

    @Override
    public void onCreate(SessionID sessionId) {
      // no-op
    }

    @Override
    public void onLogon(SessionID sessionId) {
      loggedOn.set(true);
    }

    @Override
    public void onLogout(SessionID sessionId) {
      loggedOn.set(false);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
      // no-op
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
      // no-op
    }

    @Override
    public void toApp(Message message, SessionID sessionId) {
      // no-op
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) {
      if (message instanceof ExecutionReport er) {
        reports.add(er);
      }
    }
  }
}
