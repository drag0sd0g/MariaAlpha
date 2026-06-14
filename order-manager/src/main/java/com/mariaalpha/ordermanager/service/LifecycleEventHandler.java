package com.mariaalpha.ordermanager.service;

import com.mariaalpha.ordermanager.cache.RedisPositionCachePublisher;
import com.mariaalpha.ordermanager.controller.dto.PositionSnapshot;
import com.mariaalpha.ordermanager.model.OrderLifecycleEvent;
import com.mariaalpha.ordermanager.publisher.PositionUpdatePublisher;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one order-lifecycle event: upserts the order, persists the fill (idempotently), updates
 * the position, and fans the snapshot out to Kafka/Redis.
 *
 * <p>Lives in its own bean (rather than as a {@code @Transactional} method on the Kafka consumer)
 * because Spring transactions are proxy-based: a listener calling a {@code @Transactional} method
 * on {@code this} bypasses the proxy and silently runs without a surrounding transaction. Routing
 * the call through this bean makes order + fill + position updates atomic.
 */
@Service
public class LifecycleEventHandler {

  private static final Logger LOG = LoggerFactory.getLogger(LifecycleEventHandler.class);

  private final OrderPersistenceService persistenceService;
  private final PositionService positionService;
  private final PositionUpdatePublisher publisher;
  private final ObjectProvider<RedisPositionCachePublisher> cachePublisher;

  public LifecycleEventHandler(
      OrderPersistenceService persistenceService,
      PositionService positionService,
      PositionUpdatePublisher publisher,
      ObjectProvider<RedisPositionCachePublisher> cachePublisher) {
    this.persistenceService = persistenceService;
    this.positionService = positionService;
    this.publisher = publisher;
    this.cachePublisher = cachePublisher;
  }

  @Transactional
  public void handle(OrderLifecycleEvent event) {
    if (event == null || event.order() == null) {
      LOG.warn("dropping lifecycle event with null payload");
      return;
    }
    var orderEntity = persistenceService.upsertOrder(event);
    var persisted = persistenceService.persistFillIfAbsent(orderEntity, event.fill());
    persisted.ifPresent(
        fill -> {
          var position = positionService.applyFill(fill);
          var positionSnapshot =
              new PositionSnapshot(
                  position.getSymbol(),
                  position.getNetQuantity(),
                  position.getAvgEntryPrice(),
                  position.getRealizedPnl(),
                  position.getUnrealizedPnl(),
                  position.getLastMarkPrice(),
                  Instant.now());
          publisher.publish(positionSnapshot);
          cachePublisher.ifAvailable(p -> p.publish(positionSnapshot));
        });
  }
}
