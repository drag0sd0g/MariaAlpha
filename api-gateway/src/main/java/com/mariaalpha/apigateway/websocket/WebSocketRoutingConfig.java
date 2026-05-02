package com.mariaalpha.apigateway.websocket;

import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

@Configuration
public class WebSocketRoutingConfig {

  @Bean
  public WebSocketHandlerAdapter webSocketHandlerAdapter() {
    return new WebSocketHandlerAdapter();
  }

  @Bean
  public HandlerMapping webSocketHandlerMapping(
      WebSocketProperties properties, KafkaTopicBroadcaster broadcaster) {
    Map<String, WebSocketHandler> map = new HashMap<>();
    if (properties.endpoints() != null) {
      properties
          .endpoints()
          .forEach(
              (name, endpoint) ->
                  map.put(
                      endpoint.path(),
                      new StreamingWebSocketHandler(
                          name, endpoint.topic(), endpoint.filterKey(), broadcaster)));
    }
    var mapping = new SimpleUrlHandlerMapping(map);
    // Higher precedence than SCG's own RoutePredicateHandlerMapping so the WS upgrades
    // we own are not accidentally routed to a backend.
    mapping.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return mapping;
  }
}
