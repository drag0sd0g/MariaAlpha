package com.mariaalpha.strategyengine.controller;

import com.mariaalpha.strategyengine.backtest.BacktestEngine;
import com.mariaalpha.strategyengine.backtest.BacktestRequest;
import com.mariaalpha.strategyengine.backtest.BacktestResult;
import com.mariaalpha.strategyengine.backtest.report.HtmlReportWriter;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs a backtest against real Alpaca historical data and returns the metrics + equity curve as
 * JSON. Each run also writes a self-contained HTML report; the most recent one is served from
 * {@code GET /api/backtest/report} so it can be opened directly in a browser.
 */
@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

  private static final Logger LOG = LoggerFactory.getLogger(BacktestController.class);

  private final BacktestEngine engine;
  private final HtmlReportWriter reportWriter;
  private volatile String lastReportHtml;

  public BacktestController(BacktestEngine engine, HtmlReportWriter reportWriter) {
    this.engine = engine;
    this.reportWriter = reportWriter;
  }

  @PostMapping
  public ResponseEntity<BacktestResult> run(@RequestBody BacktestRequest request) {
    BacktestResult result;
    try {
      result = engine.run(request);
    } catch (IllegalArgumentException e) {
      LOG.warn("Rejected backtest request: {}", e.getMessage());
      return ResponseEntity.badRequest().build();
    }

    var html = reportWriter.render(result);
    this.lastReportHtml = html;
    String reportPath = null;
    try {
      reportPath = reportWriter.writeRendered(result, html);
    } catch (IOException e) {
      LOG.warn("Backtest ran but the HTML report could not be written: {}", e.getMessage());
    }
    return ResponseEntity.ok(result.withReportPath(reportPath));
  }

  @GetMapping(value = "/report", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> lastReport() {
    var html = lastReportHtml;
    if (html == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(html);
  }
}
