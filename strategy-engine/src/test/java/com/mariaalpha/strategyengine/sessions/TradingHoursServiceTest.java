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
    // Saturday 3am — well outside any reasonable session.
    assertThat(disabled.isMarketOpen("AAPL", Instant.parse("2026-06-13T03:00:00Z"))).isTrue();
  }

  @Test
  void nyseOpenAt10AmEt() {
    // 14:30Z = 10:30 ET — inside the regular session on a Monday.
    assertThat(enabledService().isMarketOpen("AAPL", Instant.parse("2026-06-08T14:30:00Z")))
        .isTrue();
  }

  @Test
  void nyseClosedBefore930Et() {
    // 13:00Z = 09:00 ET — half an hour before the open.
    assertThat(enabledService().isMarketOpen("AAPL", Instant.parse("2026-06-08T13:00:00Z")))
        .isFalse();
  }

  @Test
  void nyseClosedAfter4PmEt() {
    // 20:30Z = 16:30 ET — half an hour after the close.
    assertThat(enabledService().isMarketOpen("AAPL", Instant.parse("2026-06-08T20:30:00Z")))
        .isFalse();
  }

  @Test
  void nyseClosedOnWeekends() {
    // 2026-06-13 is a Saturday.
    assertThat(enabledService().isMarketOpen("AAPL", Instant.parse("2026-06-13T14:30:00Z")))
        .isFalse();
  }

  @Test
  void nyseClosedOnConfiguredHoliday() {
    // 2026-07-03 is the observed Independence Day holiday on the NYSE config above.
    assertThat(enabledService().isMarketOpen("AAPL", Instant.parse("2026-07-03T14:30:00Z")))
        .isFalse();
  }

  @Test
  void tseHandlesZenbaAndGobaWithLunchBreakClosed() {
    var service = enabledService();
    // 09:30 JST Monday — inside Zenba.
    assertThat(service.isMarketOpen("7203", Instant.parse("2026-06-08T00:30:00Z"))).isTrue();
    // 12:00 JST Monday — inside the one-hour lunch break.
    assertThat(service.isMarketOpen("7203", Instant.parse("2026-06-08T03:00:00Z"))).isFalse();
    // 13:00 JST Monday — inside Goba.
    assertThat(service.isMarketOpen("7203", Instant.parse("2026-06-08T04:00:00Z"))).isTrue();
  }

  @Test
  void unknownSymbolFallsBackToDefaultMarket() {
    assertThat(enabledService().marketFor("XYZ")).isEqualTo("NYSE");
  }

  @Test
  void unknownMarketAllowsTickThroughForSafety() {
    // Symbol mapped to a market that is NOT in the schedules → service should not silently block
    // every tick; safer to let it through than to assume the desk meant to halt trading.
    var config =
        new TradingHoursConfig(
            true, "MYSTERY", Map.of("NYSE", NYSE), Map.of()); // default→MYSTERY not in schedules
    var svc = new TradingHoursService(config);
    assertThat(svc.isMarketOpen("AAPL", Instant.parse("2026-06-08T03:00:00Z"))).isTrue();
  }

  @Test
  void symbolOverrideTakesPrecedenceOverDefault() {
    assertThat(enabledService().marketFor("7203")).isEqualTo("TSE");
  }
}
