package com.mariaalpha.executionengine.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.executionengine.config.AlpacaConfig;
import com.mariaalpha.executionengine.model.ExecutionReport;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlpacaExchangeAdapterTest {

  private AlpacaExchangeAdapter adapter;
  private ObjectMapper objectMapper;
  private AlpacaConfig config;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    config =
        new AlpacaConfig(
            "test-key",
            "test-secret",
            "https://paper-api.alpaca.markets",
            "https://data.alpaca.markets",
            "wss://paper-api.alpaca.markets/stream",
            "ALPACA");
    adapter = new AlpacaExchangeAdapter(config, objectMapper);
  }

  @Test
  void isHealthyReflectsWsState() {
    // Before start(), adapter is not connected
    assertThat(adapter.isHealthy()).isFalse();
  }

  @Test
  void webSocketListenerParsesTradeUpdate() {
    var callback = new AtomicReference<ExecutionReport>();
    var connected = new AtomicBoolean(false);
    var listener = new AlpacaWebSocketListener(config, objectMapper, callback::set, connected);

    // Simulate onOpen
    var mockWs = mock(okhttp3.WebSocket.class);
    listener.onOpen(mockWs, mock(okhttp3.Response.class));
    assertThat(connected.get()).isTrue();

    // Simulate fill message
    var json =
        """
        {
          "stream": "trade_updates",
          "data": {
            "event": "fill",
            "price": "150.25",
            "qty": 100,
            "timestamp": "2026-04-15T14:30:00Z",
            "order": {
              "id": "alpaca-order-123",
              "qty": 100,
              "filled_qty": 100
            }
          }
        }
        """;
    listener.onMessage(mockWs, json);
    assertThat(callback.get()).isNotNull();
    assertThat(callback.get().exchangeOrderId()).isEqualTo("alpaca-order-123");
    assertThat(callback.get().fillPrice()).isEqualByComparingTo(new BigDecimal("150.25"));
    assertThat(callback.get().fillQuantity()).isEqualTo(100);
    assertThat(callback.get().remainingQuantity()).isEqualTo(0);
  }

  @Test
  void webSocketDisconnectSetsUnhealthy() {
    var connected = new AtomicBoolean(true);
    var listener = new AlpacaWebSocketListener(config, objectMapper, r -> {}, connected);
    var mockWs = mock(okhttp3.WebSocket.class);

    listener.onClosed(mockWs, 1000, "normal");
    assertThat(connected.get()).isFalse();
  }

  @Test
  void webSocketFailureSetsUnhealthy() {
    var connected = new AtomicBoolean(true);
    var listener = new AlpacaWebSocketListener(config, objectMapper, r -> {}, connected);
    var mockWs = mock(okhttp3.WebSocket.class);

    listener.onFailure(mockWs, new RuntimeException("connection lost"), null);
    assertThat(connected.get()).isFalse();
  }
}
