package com.mariaalpha.ordermanager.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.ordermanager.model.DataSource;
import com.mariaalpha.ordermanager.model.EventType;
import com.mariaalpha.ordermanager.model.MarketTickEvent;
import com.mariaalpha.ordermanager.service.PositionService;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketDataConsumerTest {

  @Mock private PositionService positionService;
  private ObjectMapper objectMapper;
  private MarketDataConsumer consumer;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    consumer = new MarketDataConsumer(objectMapper, positionService);
  }

  @Test
  void tradeTickUpdatesMarkPrice() throws Exception {
    var tick =
        new MarketTickEvent(
            "AAPL",
            Instant.now(),
            EventType.TRADE,
            BigDecimal.valueOf(175.25),
            100L,
            null,
            null,
            null,
            null,
            1_000_000L,
            DataSource.SIMULATED,
            false);
    consumer.onTick(record(tick));

    verify(positionService).updateMarkPrice("AAPL", BigDecimal.valueOf(175.25));
  }

  @Test
  void quoteTickUsesMidPrice() throws Exception {
    var tick =
        new MarketTickEvent(
            "AAPL",
            Instant.now(),
            EventType.QUOTE,
            null,
            null,
            BigDecimal.valueOf(150),
            BigDecimal.valueOf(152),
            null,
            null,
            null,
            DataSource.SIMULATED,
            false);
    consumer.onTick(record(tick));

    verify(positionService)
        .updateMarkPrice(eq("AAPL"), argThat(p -> p.compareTo(BigDecimal.valueOf(151)) == 0));
  }

  @Test
  void staleTickIsIgnored() throws Exception {
    var tick =
        new MarketTickEvent(
            "AAPL",
            Instant.now(),
            EventType.TRADE,
            BigDecimal.valueOf(150),
            1L,
            null,
            null,
            null,
            null,
            0L,
            DataSource.SIMULATED,
            true);
    consumer.onTick(record(tick));

    verify(positionService, never()).updateMarkPrice(any(), any());
  }

  @Test
  void tickWithoutPriceIsIgnored() throws Exception {
    var tick =
        new MarketTickEvent(
            "AAPL",
            Instant.now(),
            EventType.BAR,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            DataSource.SIMULATED,
            false);
    consumer.onTick(record(tick));

    verify(positionService, never()).updateMarkPrice(any(), any());
  }

  @Test
  void malformedPayloadIsSwallowed() {
    ConsumerRecord<String, String> bad =
        new ConsumerRecord<>("market-data.ticks", 0, 0, "AAPL", "not json");
    consumer.onTick(bad);
    verify(positionService, never()).updateMarkPrice(any(), any());
  }

  @Test
  void scheduledMarkToMarketDelegatesToService() {
    consumer.runMarkToMarket();
    verify(positionService).markToMarket();
  }

  @Test
  void priceFieldPreferredOverBidAsk() throws Exception {
    var tick =
        new MarketTickEvent(
            "AAPL",
            Instant.now(),
            EventType.TRADE,
            BigDecimal.valueOf(175),
            10L,
            BigDecimal.valueOf(170),
            BigDecimal.valueOf(180),
            null,
            null,
            null,
            DataSource.SIMULATED,
            false);
    consumer.onTick(record(tick));

    verify(positionService).updateMarkPrice(eq("AAPL"), eq(BigDecimal.valueOf(175)));
  }

  private ConsumerRecord<String, String> record(MarketTickEvent tick) throws Exception {
    return new ConsumerRecord<>(
        "market-data.ticks", 0, 0, tick.symbol(), objectMapper.writeValueAsString(tick));
  }
}
