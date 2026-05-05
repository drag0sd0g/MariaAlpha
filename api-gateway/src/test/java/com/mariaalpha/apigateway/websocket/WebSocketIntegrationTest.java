package com.mariaalpha.apigateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Mono;

@Tag("integration")
@Testcontainers
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WebSocketIntegrationTest {

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:latest");

  @LocalServerPort int port;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("MARIAALPHA_API_KEY", () -> "ws-test-key");
    registry.add("STRATEGY_ENGINE_URL", () -> "http://localhost:65535");
    registry.add("ORDER_MANAGER_URL", () -> "http://localhost:65535");
    registry.add("EXECUTION_ENGINE_URL", () -> "http://localhost:65535");
    registry.add("POST_TRADE_URL", () -> "http://localhost:65535");
    registry.add("ANALYTICS_SERVICE_URL", () -> "http://localhost:65535");
    registry.add("MARKET_DATA_GATEWAY_URL", () -> "http://localhost:65535");
  }

  @Test
  void marketDataTicksPropagateToWebSocketClient() throws Exception {
    var producer = producer();
    var client = new ReactorNettyWebSocketClient();
    var received = new CopyOnWriteArrayList<String>();

    URI uri = URI.create("ws://localhost:" + port + "/ws/market-data?apiKey=ws-test-key");

    var session =
        client
            .execute(
                uri,
                ws ->
                    ws.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::add)
                        .then())
            .subscribe();

    // Wait for client to connect.
    Thread.sleep(2_000);

    producer.send(
        new ProducerRecord<>(
            "market-data.ticks", "AAPL", "{\"symbol\":\"AAPL\",\"price\":178.52}"));
    producer.flush();

    Thread.sleep(2_000);
    session.dispose();
    producer.close();

    assertThat(received).anySatisfy(msg -> assertThat(msg).contains("AAPL"));
  }

  @Test
  void connectionWithoutApiKeyIsRejected() {
    var client = new ReactorNettyWebSocketClient();
    URI uri = URI.create("ws://localhost:" + port + "/ws/market-data");

    // The WebFilter returns 401 before the upgrade completes. ReactorNettyWebSocketClient surfaces
    // this as a failure on the Mono — assert that, otherwise a regression that lets the upgrade
    // succeed without a key would silently pass.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> client.execute(uri, ws -> Mono.empty()).block(Duration.ofSeconds(5)))
        .isInstanceOf(Exception.class);
  }

  @Test
  void filterBySymbolDropsNonMatching() throws Exception {
    var producer = producer();
    var client = new ReactorNettyWebSocketClient();
    var received = new CopyOnWriteArrayList<String>();

    URI uri =
        URI.create("ws://localhost:" + port + "/ws/market-data?symbol=AAPL&apiKey=ws-test-key");

    var session =
        client
            .execute(
                uri,
                ws ->
                    ws.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::add)
                        .then())
            .subscribe();

    Thread.sleep(2_000);

    producer.send(
        new ProducerRecord<>("market-data.ticks", "TSLA", "{\"symbol\":\"TSLA\",\"price\":200}"));
    producer.send(
        new ProducerRecord<>(
            "market-data.ticks", "AAPL", "{\"symbol\":\"AAPL\",\"price\":178.52}"));
    producer.flush();

    Thread.sleep(2_000);
    session.dispose();
    producer.close();

    assertThat(received).noneMatch(msg -> msg.contains("TSLA"));
    assertThat(received).anySatisfy(msg -> assertThat(msg).contains("AAPL"));
  }

  private KafkaProducer<String, String> producer() {
    return new KafkaProducer<>(
        Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
  }
}
