package com.mariaalpha.apigateway.fix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import quickfix.Acceptor;
import quickfix.DefaultMessageFactory;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

@Component
public class FixGatewayServer implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(FixGatewayServer.class);

  private final FixGatewayProperties properties;
  private final FixApplication application;
  private volatile Acceptor acceptor;
  private volatile boolean running;

  public FixGatewayServer(FixGatewayProperties properties, FixApplication application) {
    this.properties = properties;
    this.application = application;
  }

  @Override
  public void start() {
    if (!properties.enabled()) {
      LOG.info("FIX gateway disabled (mariaalpha.fix.enabled=false)");
      return;
    }
    try {
      SessionSettings settings = buildSettings();
      MessageStoreFactory storeFactory = new MemoryStoreFactory();
      LogFactory logFactory = new SLF4JLogFactory(settings);
      MessageFactory messageFactory = new DefaultMessageFactory();
      acceptor =
          new SocketAcceptor(application, storeFactory, settings, logFactory, messageFactory);
      acceptor.start();
      running = true;
      LOG.info(
          "FIX 4.4 acceptor listening on {}:{} (SenderCompID={})",
          properties.host(),
          properties.port(),
          properties.senderCompId());
    } catch (Exception e) {
      LOG.error("Failed to start FIX acceptor on {}:{}", properties.host(), properties.port(), e);
    }
  }

  @Override
  public void stop() {
    if (acceptor != null) {
      acceptor.stop();
      running = false;
      LOG.info("FIX acceptor stopped");
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private SessionSettings buildSettings() {
    SessionSettings settings = new SessionSettings();
    settings.setString("ConnectionType", "acceptor");
    settings.setString("SocketAcceptHost", properties.host());
    settings.setLong("SocketAcceptPort", properties.port());
    settings.setString("StartTime", "00:00:00");
    settings.setString("EndTime", "00:00:00");

    SessionID sessionId =
        new SessionID("FIX.4.4", properties.senderCompId(), properties.targetCompId());
    settings.setString(sessionId, "BeginString", "FIX.4.4");
    settings.setString(sessionId, "SenderCompID", properties.senderCompId());
    settings.setString(sessionId, "TargetCompID", properties.targetCompId());
    settings.setString(sessionId, "ConnectionType", "acceptor");
    settings.setString(sessionId, "StartTime", "00:00:00");
    settings.setString(sessionId, "EndTime", "00:00:00");
    settings.setLong(sessionId, "HeartBtInt", properties.heartbeatSeconds());
    settings.setLong(sessionId, "SocketAcceptPort", properties.port());
    settings.setString(sessionId, "DataDictionary", "FIX44.xml");
    return settings;
  }
}
