package com.mariaalpha.executionengine.router;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RoutingDecision;
import com.mariaalpha.executionengine.publisher.RoutingDecisionPublisher;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "execution-engine.sor.mode", havingValue = "direct")
public class DirectRouter implements SmartOrderRouter {

  private static final String VENUE = "PRIMARY";

  private final RoutingDecisionPublisher publisher;

  public DirectRouter(RoutingDecisionPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public RoutingDecision route(Order order) {
    var decision =
        RoutingDecision.legacy(
            order.getOrderId(),
            VENUE,
            "DirectRouter: pass-through to primary exchange adapter",
            Instant.now());
    order.setVenue(VENUE);
    publisher.publish(decision);
    return decision;
  }
}
