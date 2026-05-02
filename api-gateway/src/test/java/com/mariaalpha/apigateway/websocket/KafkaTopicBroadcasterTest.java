package com.mariaalpha.apigateway.websocket;

import static org.mockito.Mockito.mock;

import com.mariaalpha.apigateway.websocket.WebSocketProperties.Endpoint;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import reactor.test.StepVerifier;

class KafkaTopicBroadcasterTest {

  private KafkaTopicBroadcaster broadcaster;

  @BeforeEach
  void setUp() {
    var props =
        new WebSocketProperties(
            Map.of(
                "market-data",
                new Endpoint("/ws/market-data", "market-data.ticks", "symbol"),
                "positions",
                new Endpoint("/ws/positions", "positions.updates", "symbol")),
            1024);
    broadcaster = new KafkaTopicBroadcaster(props, mock(KafkaListenerEndpointRegistry.class));
    broadcaster.initialize();
  }

  @Test
  void deliversToSingleSubscriber() {
    var sub = broadcaster.stream("market-data.ticks");
    StepVerifier.create(sub.take(1))
        .then(() -> broadcaster.onMarketData(record("market-data.ticks", "{\"symbol\":\"AAPL\"}")))
        .expectNext("{\"symbol\":\"AAPL\"}")
        .verifyComplete();
  }

  @Test
  void deliversToMultipleSubscribers() {
    var subA = broadcaster.stream("market-data.ticks");
    var subB = broadcaster.stream("market-data.ticks");

    StepVerifier.create(subA.take(1))
        .then(() -> broadcaster.onMarketData(record("market-data.ticks", "{\"symbol\":\"X\"}")))
        .expectNext("{\"symbol\":\"X\"}")
        .verifyComplete();

    // Second subscriber sees the next message independently.
    StepVerifier.create(subB.take(1))
        .then(() -> broadcaster.onMarketData(record("market-data.ticks", "{\"symbol\":\"Y\"}")))
        .expectNext("{\"symbol\":\"Y\"}")
        .verifyComplete();
  }

  @Test
  void unknownTopicReturnsEmptyFlux() {
    StepVerifier.create(broadcaster.stream("nope")).verifyComplete();
  }

  @Test
  void differentTopicsAreIsolated() {
    var marketData = broadcaster.stream("market-data.ticks");

    StepVerifier.create(marketData.take(1))
        .then(
            () -> {
              broadcaster.onPositions(record("positions.updates", "{\"symbol\":\"X\"}"));
              broadcaster.onMarketData(record("market-data.ticks", "{\"symbol\":\"Y\"}"));
            })
        .expectNext("{\"symbol\":\"Y\"}")
        .verifyComplete();
  }

  private static ConsumerRecord<String, String> record(String topic, String value) {
    return new ConsumerRecord<>(topic, 0, 0, null, value);
  }
}
