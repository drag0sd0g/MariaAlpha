package com.mariaalpha.strategyengine.backtest.report;

import com.mariaalpha.strategyengine.backtest.BacktestProperties;
import com.mariaalpha.strategyengine.backtest.BacktestResult;
import com.mariaalpha.strategyengine.backtest.EquityPoint;
import com.mariaalpha.strategyengine.backtest.TradeRecord;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders a backtest result into a single, self-contained HTML page (inline CSS + inline SVG equity
 * curve, no external assets) and writes it to the configured report directory. This is the
 * shareable "proof" artifact.
 */
@Component
public class HtmlReportWriter {

  private static final Logger LOG = LoggerFactory.getLogger(HtmlReportWriter.class);
  private static final DateTimeFormatter TS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).withZone(java.time.ZoneOffset.UTC);
  private static final int MAX_CURVE_POINTS = 600;
  private static final int MAX_BLOTTER_ROWS = 500;
  private static final int SVG_WIDTH = 960;
  private static final int SVG_HEIGHT = 340;

  private final BacktestProperties properties;

  public HtmlReportWriter(BacktestProperties properties) {
    this.properties = properties;
  }

  /** Renders the result to a complete HTML document. Pure — no I/O. */
  public String render(BacktestResult result) {
    var metrics = result.metrics();
    var positive = metrics.totalReturnPct() >= 0.0;
    var accent = positive ? "#128a4b" : "#c0392b";
    var html = new StringBuilder(64 * 1024);
    html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        .append("<title>MariaAlpha Backtest — ")
        .append(esc(result.strategyName()))
        .append("</title><style>")
        .append(css(accent))
        .append("</style></head><body><main>");

    html.append("<header><h1>MariaAlpha Backtest Report</h1><p class=\"sub\">")
        .append(esc(result.strategyName()))
        .append(" &middot; ")
        .append(esc(String.join(", ", result.symbols())))
        .append("<br>")
        .append(TS.format(result.from()))
        .append(" → ")
        .append(TS.format(result.to()))
        .append(" UTC &middot; ")
        .append(result.barsReplayed())
        .append(" bars, ")
        .append(result.ticksReplayed())
        .append(" ticks &middot; ML gate: ")
        .append(result.mlGateUsed() ? "on" : "off")
        .append("</p></header>");

    if (result.barsReplayed() == 0) {
      html.append("<p class=\"warn\">No historical bars were returned for this window. Check ")
          .append("Alpaca credentials, the symbol list, and that the range covers a trading ")
          .append("session (the free IEX feed excludes weekends/holidays and very recent data).")
          .append("</p>");
    }

    html.append("<section class=\"tiles\">");
    tile(html, "Total return", pct(metrics.totalReturnPct()), true);
    tile(html, "Realized P&L", money(metrics.realizedPnl()), false);
    tile(html, "Unrealized P&L", money(metrics.unrealizedPnl()), false);
    tile(html, "Sharpe (annualized)", num(metrics.sharpe()), false);
    tile(html, "Max drawdown", pct(-Math.abs(metrics.maxDrawdownPct())), false);
    tile(html, "Hit rate", pct(metrics.hitRate() * 100.0), false);
    tile(html, "Closed trades", Integer.toString(metrics.closedTrades()), false);
    tile(html, "Fills", Integer.toString(metrics.totalFills()), false);
    tile(html, "Avg slippage", num(metrics.avgSlippageBps()) + " bps", false);
    tile(
        html,
        "Final equity",
        money(metrics.finalEquity()) + " / " + money(metrics.initialEquity()),
        false);
    html.append("</section>");

    html.append("<section><h2>Equity curve</h2>")
        .append(equitySvg(result.equityCurve(), metrics.initialEquity(), accent))
        .append("</section>");

    html.append("<section><h2>Trade blotter</h2>")
        .append(blotter(result.trades()))
        .append("</section>");

    html.append("<footer><p class=\"note\">")
        .append(esc(result.dataNote()))
        .append("</p></footer></main></body></html>");
    return html.toString();
  }

  /** Renders and writes the report to the configured directory, returning the file path. */
  public String write(BacktestResult result) throws IOException {
    return writeRendered(result, render(result));
  }

  /** Writes an already-rendered document, returning the file path. */
  public String writeRendered(BacktestResult result, String html) throws IOException {
    var dir = Path.of(properties.reportDir());
    Files.createDirectories(dir);
    var file = dir.resolve("backtest-" + slug(result.strategyName()) + ".html");
    Files.writeString(file, html, StandardCharsets.UTF_8);
    var path = file.toAbsolutePath().toString();
    LOG.info("Wrote backtest report to {}", path);
    return path;
  }

  private static void tile(StringBuilder html, String label, String value, boolean hero) {
    html.append("<div class=\"tile")
        .append(hero ? " hero" : "")
        .append("\"><span class=\"label\">")
        .append(esc(label))
        .append("</span><span class=\"value\">")
        .append(esc(value))
        .append("</span></div>");
  }

  private static String equitySvg(
      List<EquityPoint> curve, BigDecimal initialEquity, String accent) {
    if (curve.size() < 2) {
      return "<p class=\"muted\">Not enough data points to plot an equity curve.</p>";
    }
    var sampled = downsample(curve);
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    for (var point : sampled) {
      double value = point.equity().doubleValue();
      min = Math.min(min, value);
      max = Math.max(max, value);
    }
    double base = initialEquity.doubleValue();
    min = Math.min(min, base);
    max = Math.max(max, base);
    double range = max - min;
    if (range <= 0) {
      range = Math.abs(max) < 1e-9 ? 1.0 : Math.abs(max) * 0.01;
    }
    int padL = 70;
    int padR = 20;
    int padT = 20;
    int padB = 40;
    double plotW = SVG_WIDTH - padL - padR;
    double plotH = SVG_HEIGHT - padT - padB;

    var path = new StringBuilder();
    for (int i = 0; i < sampled.size(); i++) {
      double x = padL + plotW * i / (sampled.size() - 1);
      double y = padT + plotH * (1.0 - (sampled.get(i).equity().doubleValue() - min) / range);
      path.append(i == 0 ? "M" : "L").append(fmt(x)).append(' ').append(fmt(y)).append(' ');
    }
    double baseY = padT + plotH * (1.0 - (base - min) / range);

    var svg = new StringBuilder();
    svg.append("<svg viewBox=\"0 0 ")
        .append(SVG_WIDTH)
        .append(' ')
        .append(SVG_HEIGHT)
        .append("\" class=\"equity\" role=\"img\">");
    svg.append("<line x1=\"")
        .append(padL)
        .append("\" y1=\"")
        .append(fmt(baseY))
        .append("\" x2=\"")
        .append(SVG_WIDTH - padR)
        .append("\" y2=\"")
        .append(fmt(baseY))
        .append("\" class=\"baseline\"/>");
    svg.append("<path d=\"")
        .append(path)
        .append("\" fill=\"none\" stroke=\"")
        .append(accent)
        .append("\" stroke-width=\"2\"/>");
    svg.append(axisLabel(padL - 8, padT + 6, money(BigDecimal.valueOf(max)), "end"));
    svg.append(axisLabel(padL - 8, padT + plotH, money(BigDecimal.valueOf(min)), "end"));
    svg.append(axisLabel(padL, SVG_HEIGHT - 12, TS.format(sampled.get(0).timestamp()), "start"));
    svg.append(
        axisLabel(
            SVG_WIDTH - padR,
            SVG_HEIGHT - 12,
            TS.format(sampled.get(sampled.size() - 1).timestamp()),
            "end"));
    svg.append("</svg>");
    return svg.toString();
  }

  private static String axisLabel(double x, double y, String text, String anchor) {
    return "<text x=\""
        + fmt(x)
        + "\" y=\""
        + fmt(y)
        + "\" text-anchor=\""
        + anchor
        + "\" class=\"axis\">"
        + esc(text)
        + "</text>";
  }

  private static List<EquityPoint> downsample(List<EquityPoint> curve) {
    if (curve.size() <= MAX_CURVE_POINTS) {
      return curve;
    }
    int stride = (int) Math.ceil((double) curve.size() / MAX_CURVE_POINTS);
    var out = new java.util.ArrayList<EquityPoint>();
    for (int i = 0; i < curve.size(); i += stride) {
      out.add(curve.get(i));
    }
    var last = curve.get(curve.size() - 1);
    if (!out.get(out.size() - 1).equals(last)) {
      out.add(last);
    }
    return out;
  }

  private static String blotter(List<TradeRecord> trades) {
    if (trades.isEmpty()) {
      return "<p class=\"muted\">No trades were generated.</p>";
    }
    var table = new StringBuilder();
    table
        .append("<table><thead><tr><th>Time (UTC)</th><th>Symbol</th><th>Side</th>")
        .append("<th class=\"r\">Qty</th><th class=\"r\">Price</th><th class=\"r\">Arrival</th>")
        .append("<th class=\"r\">Slippage (bps)</th><th class=\"r\">Realized P&L</th><th>Type</th>")
        .append("</tr></thead><tbody>");
    int rows = Math.min(trades.size(), MAX_BLOTTER_ROWS);
    for (int i = 0; i < rows; i++) {
      var trade = trades.get(i);
      table
          .append("<tr><td>")
          .append(TS.format(trade.timestamp()))
          .append("</td><td>")
          .append(esc(trade.symbol()))
          .append("</td><td class=\"")
          .append(trade.side().name().toLowerCase(Locale.US))
          .append("\">")
          .append(trade.side())
          .append("</td><td class=\"r\">")
          .append(trade.quantity())
          .append("</td><td class=\"r\">")
          .append(money(trade.price()))
          .append("</td><td class=\"r\">")
          .append(money(trade.arrivalMid()))
          .append("</td><td class=\"r\">")
          .append(num(trade.slippageBps()))
          .append("</td><td class=\"r\">")
          .append(money(trade.realizedPnl()))
          .append("</td><td>")
          .append(trade.passive() ? "passive" : "aggressive")
          .append("</td></tr>");
    }
    table.append("</tbody></table>");
    if (trades.size() > rows) {
      table
          .append("<p class=\"muted\">Showing first ")
          .append(rows)
          .append(" of ")
          .append(trades.size())
          .append(" fills.</p>");
    }
    return table.toString();
  }

  private static String css(String accent) {
    return "*{box-sizing:border-box}body{margin:0;font-family:-apple-system,Segoe UI,Roboto,"
        + "Helvetica,Arial,sans-serif;color:#1c2530;background:#f4f6f8}main{max-width:1040px;"
        + "margin:0 auto;padding:32px 24px}h1{font-size:24px;margin:0}h2{font-size:16px;"
        + "border-bottom:1px solid #dfe4ea;padding-bottom:6px;margin-top:36px}.sub{color:#5b6774;"
        + "font-size:13px;line-height:1.5}.tiles{display:grid;grid-template-columns:repeat("
        + "auto-fill,minmax(180px,1fr));gap:12px;margin-top:20px}.tile{background:#fff;border:1px "
        + "solid #e3e8ee;border-radius:10px;padding:14px 16px;display:flex;flex-direction:column;"
        + "gap:6px}.tile .label{font-size:11px;text-transform:uppercase;letter-spacing:.04em;"
        + "color:#788390}.tile .value{font-size:20px;font-weight:600;font-variant-numeric:"
        + "tabular-nums}.tile.hero{border-color:"
        + accent
        + "}.tile.hero .value{color:"
        + accent
        + "}svg.equity{width:100%;height:auto;background:#fff;border:1px solid #e3e8ee;"
        + "border-radius:10px;margin-top:8px}.equity .baseline{stroke:#b6c0cc;stroke-width:1;"
        + "stroke-dasharray:4 4}.equity .axis{fill:#788390;font-size:11px}table{width:100%;"
        + "border-collapse:collapse;font-size:13px;margin-top:8px}th,td{padding:6px 10px;"
        + "border-bottom:1px solid #eceff3;text-align:left}th.r,td.r{text-align:right;"
        + "font-variant-numeric:tabular-nums}td.buy{color:#128a4b;font-weight:600}td.sell{"
        + "color:#c0392b;font-weight:600}.note{color:#5b6774;font-size:12px;font-style:italic;"
        + "margin-top:24px}.muted{color:#788390;font-size:13px}.warn{background:#fff4e5;border:1px "
        + "solid #ffd9a8;border-radius:8px;padding:12px 16px;font-size:13px}";
  }

  private static String pct(double value) {
    return String.format(Locale.US, "%+.2f%%", value);
  }

  private static String num(double value) {
    return String.format(Locale.US, "%.2f", value);
  }

  private static String money(BigDecimal value) {
    if (value == null) {
      return "—";
    }
    return String.format(Locale.US, "%,.2f", value.setScale(2, RoundingMode.HALF_UP));
  }

  private static String fmt(double value) {
    return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
  }

  private static String slug(String value) {
    return value.replaceAll("[^A-Za-z0-9_-]", "_");
  }

  private static String esc(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
