package com.mariaalpha.strategyengine.backtest.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.strategyengine.backtest.BacktestMetrics;
import com.mariaalpha.strategyengine.backtest.BacktestProperties;
import com.mariaalpha.strategyengine.backtest.BacktestResult;
import com.mariaalpha.strategyengine.backtest.EquityPoint;
import com.mariaalpha.strategyengine.backtest.TradeRecord;
import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlReportWriterTest {

  private static final Instant T0 = Instant.parse("2026-03-24T14:30:00Z");

  private static BacktestResult sampleResult() {
    var metrics =
        new BacktestMetrics(
            1.50,
            2.0,
            3.0,
            0.6,
            5,
            10,
            0.5,
            BigDecimal.valueOf(100_000),
            BigDecimal.valueOf(101_500),
            BigDecimal.valueOf(1_500),
            BigDecimal.ZERO);
    var curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100_000)),
            new EquityPoint(T0.plusSeconds(60), BigDecimal.valueOf(100_800)),
            new EquityPoint(T0.plusSeconds(120), BigDecimal.valueOf(101_500)));
    var trades =
        List.of(
            new TradeRecord(
                T0,
                "AAPL",
                Side.BUY,
                100,
                new BigDecimal("100.10"),
                new BigDecimal("100.00"),
                10.0,
                BigDecimal.ZERO,
                false));
    return new BacktestResult(
        "MOMENTUM",
        List.of("AAPL"),
        T0,
        T0.plusSeconds(180),
        3,
        12,
        false,
        "note",
        metrics,
        curve,
        trades,
        null);
  }

  private HtmlReportWriter writerWithDir(Path dir) {
    return new HtmlReportWriter(new BacktestProperties(null, 0, 1.0, 2.0, dir.toString()));
  }

  @Test
  void renderProducesSelfContainedHtmlWithKeyContent(@TempDir Path dir) {
    var html = writerWithDir(dir).render(sampleResult());

    assertThat(html).startsWith("<!doctype html>");
    assertThat(html).contains("MOMENTUM").contains("AAPL");
    assertThat(html).contains("Total return").contains("+1.50%");
    assertThat(html).contains("<svg"); // inline equity curve, no external assets
    assertThat(html).doesNotContain("http://").doesNotContain("https://cdn");
    assertThat(html).contains("<table>"); // trade blotter
  }

  @Test
  void writeCreatesReportFile(@TempDir Path dir) throws Exception {
    var writer = writerWithDir(dir);
    var path = writer.write(sampleResult());

    assertThat(Path.of(path)).exists();
    assertThat(Files.readString(Path.of(path))).contains("MariaAlpha Backtest Report");
  }
}
