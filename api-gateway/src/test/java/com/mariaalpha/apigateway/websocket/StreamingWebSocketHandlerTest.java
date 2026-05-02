package com.mariaalpha.apigateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.apigateway.websocket.WebSocketProperties.Endpoint;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class StreamingWebSocketHandlerTest {

  @Test
  void unfilteredHandlerForwardsAllRecords() {
    var props =
        new WebSocketProperties(
            Map.of("md", new Endpoint("/ws/market-data", "market-data.ticks", "symbol")), 1024);
    var broadcaster = new KafkaTopicBroadcaster(props, mock(KafkaListenerEndpointRegistry.class));
    broadcaster.initialize();

    var handler = new StreamingWebSocketHandler("md", "market-data.ticks", "symbol", broadcaster);

    var session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("s1");
    var info =
        new HandshakeInfo(
            URI.create("ws://localhost/ws/market-data"), HttpHeaders.EMPTY, Mono.empty(), null);
    when(session.getHandshakeInfo()).thenReturn(info);
    when(session.textMessage(any())).thenReturn(mock(WebSocketMessage.class));
    when(session.send(any())).thenReturn(Mono.empty());

    handler.handle(session).subscribe();
    broadcaster.onMarketData(
        new org.apache.kafka.clients.consumer.ConsumerRecord<>(
            "market-data.ticks", 0, 0, null, "{\"symbol\":\"AAPL\"}"));

    @SuppressWarnings("unchecked")
    var captor = ArgumentCaptor.forClass(Flux.class);
    org.mockito.Mockito.verify(session, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
    assertThat(captor.getValue()).isNotNull();
  }
}
