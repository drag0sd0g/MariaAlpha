package com.mariaalpha.posttrade.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MirroredAlpacaActivitiesClientTest {

  private final MirroredAlpacaActivitiesClient client = new MirroredAlpacaActivitiesClient();

  @Test
  void emptyInternalProducesEmptyExternal() {
    assertThat(client.activitiesForDate(LocalDate.of(2026, 6, 1), List.of())).isEmpty();
  }

  @Test
  void everyInternalFillIsEchoedAsExternal() {
    var f =
        new InternalFill(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "alpaca-1",
            UUID.randomUUID().toString(),
            "AAPL",
            Side.BUY,
            new BigDecimal("180.05"),
            new BigDecimal("100"),
            Instant.parse("2026-06-01T15:30:00Z"));
    var out = client.activitiesForDate(LocalDate.of(2026, 6, 1), List.of(f));
    assertThat(out).hasSize(1);
    var e = out.get(0);
    assertThat(e.exchangeOrderId()).isEqualTo("alpaca-1");
    assertThat(e.clientOrderId()).isEqualTo(f.clientOrderId());
    assertThat(e.symbol()).isEqualTo("AAPL");
    assertThat(e.side()).isEqualTo(Side.BUY);
    assertThat(e.price()).isEqualByComparingTo("180.05");
    assertThat(e.quantity()).isEqualByComparingTo("100");
  }
}
