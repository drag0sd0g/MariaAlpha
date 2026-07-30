package com.mariaalpha.executionengine.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.risk.PortfolioRiskModel;
import com.mariaalpha.executionengine.risk.RiskModelSnapshot;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

/**
 * Consumes the covariance model analytics-service publishes on {@code analytics.risk-model}
 * (roadmap 4.6.1) and hands it to {@link PortfolioRiskModel}.
 *
 * <p><strong>Why it seeks to the beginning on every assignment.</strong> This topic is a compacted
 * <em>latest-state</em> log — a KTable, not a queue. The consumer does not want "everything since
 * my last commit", it wants "whatever the current model is", every time it starts.
 *
 * <p>{@code auto.offset.reset=earliest} alone is not enough: it only applies when the group has
 * <em>no</em> committed offset. After the first run the group has committed past the model, so a
 * restart resumes at the end of the log and the check silently runs on the conservative
 * sum-of-absolutes fallback until the publisher's next tick — up to five minutes of a needlessly
 * tighter risk limit, and longer if the publish interval is raised. Seeking to the beginning of
 * the (compacted) partition on assignment makes startup deterministic: the latest retained model
 * per key is always replayed.
 *
 * <p>Like {@link MarketDataConsumer}, this listener never lets an exception escape: a malformed
 * model must not stall the consumer group or poison the partition. A rejected payload simply leaves
 * the previous model in place, which stales out on its own if the problem persists.
 */
@Component
public class RiskModelConsumer implements ConsumerSeekAware {

  private static final Logger LOG = LoggerFactory.getLogger(RiskModelConsumer.class);

  private final ObjectMapper objectMapper;
  private final PortfolioRiskModel model;

  public RiskModelConsumer(ObjectMapper objectMapper, PortfolioRiskModel model) {
    this.objectMapper = objectMapper;
    this.model = model;
  }

  @KafkaListener(
      topics = "${execution-engine.kafka.risk-model-topic}",
      groupId = "execution-engine-risk-model",
      properties = {"auto.offset.reset=earliest"})
  public void onModel(ConsumerRecord<String, String> record) {
    try {
      var snapshot = objectMapper.readValue(record.value(), RiskModelSnapshot.class);
      model.update(snapshot);
    } catch (Exception e) {
      LOG.error("Failed to apply risk model from offset {}: {}", record.offset(), e.getMessage());
    }
  }

  @Override
  public void onPartitionsAssigned(
      Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
    if (assignments.isEmpty()) {
      return;
    }
    LOG.info(
        "Replaying the compacted risk-model log from the beginning for {} partition(s)",
        assignments.size());
    assignments.keySet().forEach(tp -> callback.seekToBeginning(tp.topic(), tp.partition()));
  }
}
