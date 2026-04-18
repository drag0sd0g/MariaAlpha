package com.mariaalpha.executionengine.router;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RoutingDecision;
import com.mariaalpha.executionengine.publisher.RoutingDecisionPublisher;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class DirectRouter implements SmartOrderRouter {

  private static final String VENUE = "PRIMARY"; // profile-dependent

  private final RoutingDecisionPublisher publisher;

  public DirectRouter(RoutingDecisionPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public RoutingDecision route(Order order) {
    var decision =
        new RoutingDecision(
            order.getOrderId(),
            VENUE,
            "DirectRouter: pass-through to primary exchange adapter",
            Instant.now());
    publisher.publish(decision);
    return decision;
  }
}
