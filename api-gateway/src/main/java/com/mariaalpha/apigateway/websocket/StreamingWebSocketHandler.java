package com.mariaalpha.apigateway.websocket;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class StreamingWebSocketHandler implements WebSocketHandler {

  private static final Logger LOG = LoggerFactory.getLogger(StreamingWebSocketHandler.class);

  private final String name;
  private final KafkaTopicBroadcaster broadcaster;
  private final String topic;
  private final String filterKey;

  public StreamingWebSocketHandler(
      String name, String topic, String filterKey, KafkaTopicBroadcaster broadcaster) {
    this.name = name;
    this.topic = topic;
    this.filterKey = filterKey;
    this.broadcaster = broadcaster;
  }

  @Override
  public Mono<Void> handle(WebSocketSession session) {
    var query = session.getHandshakeInfo().getUri().getQuery();
    var filterValue = extractQuery(query, filterKey);
    LOG.info(
        "ws {} session={} filter={}={}",
        name,
        session.getId(),
        filterKey,
        filterValue == null ? "*" : filterValue);

    Flux<String> source = broadcaster.stream(topic);
    if (filterValue != null) {
      source =
          source.filter(json -> filterValue.equals(SymbolKeyExtractor.extract(json, filterKey)));
    }

    Flux<WebSocketMessage> messages = source.onBackpressureLatest().map(session::textMessage);

    return session
        .send(messages)
        .doFinally(
            sig -> LOG.info("ws {} session={} closed signal={}", name, session.getId(), sig));
  }

  /** Tiny query-string parser to avoid pulling in Spring's UriComponents from a hot path. */
  private static String extractQuery(String query, String key) {
    if (query == null || key == null) {
      return null;
    }
    var prefix = key + "=";
    for (var pair : List.of(query.split("&"))) {
      if (pair.startsWith(prefix)) {
        return pair.substring(prefix.length());
      }
    }
    return null;
  }
}
