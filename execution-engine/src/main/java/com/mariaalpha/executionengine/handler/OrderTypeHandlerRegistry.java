package com.mariaalpha.executionengine.handler;

import com.mariaalpha.executionengine.model.OrderType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OrderTypeHandlerRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(OrderTypeHandlerRegistry.class);

  private final Map<OrderType, OrderTypeHandler> handlers;

  public OrderTypeHandlerRegistry(List<OrderTypeHandler> discoveredHandlers) {
    this.handlers = new ConcurrentHashMap<>();
    for (var handler : discoveredHandlers) {
      handlers.put(handler.supportedType(), handler);
      LOG.info(
          "Registered OrderTypeHandler {} -> {}",
          handler.supportedType(),
          handler.getClass().getSimpleName());
    }
  }

  public Optional<OrderTypeHandler> getHandler(OrderType type) {
    return Optional.ofNullable(handlers.get(type));
  }
}
