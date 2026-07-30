package com.mariaalpha.executionengine.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.executionengine.risk.PortfolioRiskModel;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerSeekAware;

class RiskModelConsumerTest {

  private static final String VALID =
      """
      {
        "modelId": "cov-20260729T140211Z",
        "generatedAt": "2026-07-29T14:02:11Z",
        "estimator": "ewma+ledoit_wolf",
        "source": "sample+prior",
        "barSeconds": 60,
        "observations": 128,
        "shrinkageIntensity": 0.34,
        "tradingDaysPerYear": 252.0,
        "symbols": ["NVDA", "MSFT"],
        "annualizedVolatility": [0.48, 0.24],
        "correlation": [[1.0, 0.42], [0.42, 1.0]],
        "psdRepaired": false,
        "conditionNumber": 18.7
      }
      """;

  private PortfolioRiskModel model;
  private RiskModelConsumer consumer;

  @BeforeEach
  void setUp() {
    model = new PortfolioRiskModel();
    var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    consumer = new RiskModelConsumer(mapper, model);
  }

  @Test
  void appliesAValidModel() {
    consumer.onModel(record(VALID));

    assertThat(model.isPresent()).isTrue();
    assertThat(model.modelId()).isEqualTo("cov-20260729T140211Z");
    assertThat(model.symbols()).containsExactly("NVDA", "MSFT");
    assertThat(model.size()).isEqualTo(2);
    assertThat(model.dailyVolatility("NVDA")).isCloseTo(0.48 / Math.sqrt(252.0), within(1e-12));
  }

  @Test
  void ignoresUnknownFieldsSoTheProducerCanEvolve() {
    var withExtras =
        VALID.replace("\"barSeconds\": 60,", "\"barSeconds\": 60, \"futureField\": 1,");
    assertThatCode(() -> consumer.onModel(record(withExtras))).doesNotThrowAnyException();
    assertThat(model.isPresent()).isTrue();
  }

  @Test
  void swallowsMalformedJsonWithoutStallingTheConsumer() {
    assertThatCode(() -> consumer.onModel(record("{not json"))).doesNotThrowAnyException();
    assertThat(model.isPresent()).as("a bad payload must not install a model").isFalse();
  }

  @Test
  void swallowsNullPayload() {
    assertThatCode(() -> consumer.onModel(record(null))).doesNotThrowAnyException();
    assertThat(model.isPresent()).isFalse();
  }

  @Test
  void rejectsStructurallyInvalidModelsAndKeepsThePreviousOne() {
    consumer.onModel(record(VALID));
    assertThat(model.isPresent()).isTrue();
    var goodId = model.modelId();

    var asymmetric = VALID.replace("[[1.0, 0.42], [0.42, 1.0]]", "[[1.0, 0.42], [0.10, 1.0]]");
    consumer.onModel(record(asymmetric));
    assertThat(model.modelId())
        .as("a rejected payload leaves the previous model in place; staleness handles the rest")
        .isEqualTo(goodId);

    var mismatched = VALID.replace("[0.48, 0.24]", "[0.48]");
    consumer.onModel(record(mismatched));
    assertThat(model.modelId()).isEqualTo(goodId);

    var badDiagonal = VALID.replace("[[1.0, 0.42], [0.42, 1.0]]", "[[0.5, 0.42], [0.42, 1.0]]");
    consumer.onModel(record(badDiagonal));
    assertThat(model.modelId()).isEqualTo(goodId);
  }

  @Test
  void seeksToTheBeginningOfEveryAssignedPartition() {
    // The topic is a compacted latest-state log. auto.offset.reset=earliest only applies when the
    // group has no committed offset, so after the first run a restart would resume past the model
    // and leave the VaR check on its conservative fallback until the next publish.
    var callback = mock(ConsumerSeekAware.ConsumerSeekCallback.class);
    consumer.onPartitionsAssigned(
        Map.of(new TopicPartition("analytics.risk-model", 0), 42L), callback);

    verify(callback).seekToBeginning("analytics.risk-model", 0);
  }

  @Test
  void noAssignmentsIsANoOp() {
    var callback = mock(ConsumerSeekAware.ConsumerSeekCallback.class);
    consumer.onPartitionsAssigned(Map.of(), callback);
    verifyNoInteractions(callback);
  }

  private static ConsumerRecord<String, String> record(String value) {
    return new ConsumerRecord<>("analytics.risk-model", 0, 0L, "GLOBAL", value);
  }
}
