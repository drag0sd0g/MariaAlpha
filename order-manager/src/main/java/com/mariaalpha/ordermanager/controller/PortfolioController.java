package com.mariaalpha.ordermanager.controller;

import com.mariaalpha.ordermanager.controller.dto.CurrencyExposureResponse;
import com.mariaalpha.ordermanager.controller.dto.PortfolioSummaryResponse;
import com.mariaalpha.ordermanager.service.CurrencyExposureService;
import com.mariaalpha.ordermanager.service.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

  private final PortfolioService portfolioService;
  private final CurrencyExposureService currencyExposureService;

  public PortfolioController(
      PortfolioService portfolioService, CurrencyExposureService currencyExposureService) {
    this.portfolioService = portfolioService;
    this.currencyExposureService = currencyExposureService;
  }

  @GetMapping("/summary")
  public PortfolioSummaryResponse summary() {
    return portfolioService.summary();
  }

  @GetMapping("/currency-exposure")
  public CurrencyExposureResponse currencyExposure() {
    return currencyExposureService.exposureByCurrency();
  }
}
