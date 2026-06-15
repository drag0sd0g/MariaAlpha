package com.mariaalpha.strategyengine.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.strategyengine.sessions.TradingHoursConfig.MarketSchedule;
import com.mariaalpha.strategyengine.sessions.TradingHoursConfig.SessionWindow;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TradingHoursServiceTest {

  private static final MarketSchedule NYSE =
      new MarketSchedule(
          ZoneId.of("America/New_York"),
          List.of(new SessionWindow(LocalTime.of(9, 30), LocalTime.of(16, 0))),
          Set.of(
              DayOfWeek.MONDAY,
              DayOfWeek.TUESDAY,
              DayOfWeek.WEDNESDAY,
              DayOfWeek.THURSDAY,
              DayOfWeek.FRIDAY),
          Set.of(LocalDate.of(2026, 7, 3)));

  private static final MarketSchedule TSE =
      new MarketSchedule(
          ZoneId.of("Asia/Tokyo"),
          List.of(
              new SessionWindow(LocalTime.of(9, 0), LocalTime.of(11, 30)),
              new SessionWindow(LocalTime.of(12, 30), LocalTime.of(15, 30))),
          Set.of(
              DayOfWeek.MONDAY,
              DayOfWeek.TUESDAY,
              DayOfWeek.WEDNESDAY,
              DayOfWeek.THURSDAY,
              DayOfWeek.FRIDAY),
          Set.of());

  private static TradingHoursService enabledService() {
    var config =
        new TradingHoursConfig(
            true, "NYSE", Map.of("NYSE", NYSE, "TSE", TSE), Map.of("7203", "TSE"));
    return new TradingHoursService(config);
  }

  @Test
  void disabledServiceTreatsEveryTimestampAsOpen() {
    var disabled =
        new TradingHoursService(new TradingHoursConfig(false, "NYSE", Map.of(), Map.of()));
    assertThat(disabled.isMarketOpen("AAPL", Instant.parse("2026-06-13T03:00:00Z"))).isTrue();
  }

  @Test
  void nyseOpenAt10AmEt() {
    assertThat(enabledService().isMarketOpen("AAPL", Instant.parse("2026-06-08T14:30:00Z")))
        .isTrue();
  }

  @Test
  void nyseClosedBefore930Et() {
    assertThat(enabledService().isMarketOpen("AAPL", Instant.parse("2026-06-08T13:00:00Z")))
        .isFalse();
  }

  @Test
  void nyseClosedAfter4PmEt() {
    assertThat(enabledService().isMarketOpen("AAPL", Instant.parse("2026-06-08T20:30:00Z")))
        .isFalse();
  }

  @Test
  void nyseClosedOnWeekends() {
    assertThat(enabledService().isMarketOpen("AAPL", Instant.parse("2026-06-13T14:30:00Z")))
        .isFalse();
  }

  @Test
  void nyseClosedOnConfiguredHoliday() {
    assertThat(enabledService().isMarketOpen("AAPL", Instant.parse("2026-07-03T14:30:00Z")))
        .isFalse();
  }

  @Test
  void tseHandlesZenbaAndGobaWithLunchBreakClosed() {
    var service = enabledService();
    assertThat(service.isMarketOpen("7203", Instant.parse("2026-06-08T00:30:00Z"))).isTrue();
    assertThat(service.isMarketOpen("7203", Instant.parse("2026-06-08T03:00:00Z"))).isFalse();
    assertThat(service.isMarketOpen("7203", Instant.parse("2026-06-08T04:00:00Z"))).isTrue();
  }

  @Test
  void unknownSymbolFallsBackToDefaultMarket() {
    assertThat(enabledService().marketFor("XYZ")).isEqualTo("NYSE");
  }

  @Test
  void unknownMarketAllowsTickThroughForSafety() {
    var config = new TradingHoursConfig(true, "MYSTERY", Map.of("NYSE", NYSE), Map.of());
    var svc = new TradingHoursService(config);
    assertThat(svc.isMarketOpen("AAPL", Instant.parse("2026-06-08T03:00:00Z"))).isTrue();
  }

  @Test
  void symbolOverrideTakesPrecedenceOverDefault() {
    assertThat(enabledService().marketFor("7203")).isEqualTo("TSE");
  }
}
