package com.mariaalpha.ordermanager.controller;

import com.mariaalpha.ordermanager.controller.dto.PortfolioSummaryResponse;
import com.mariaalpha.ordermanager.service.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

  private final PortfolioService portfolioService;

  public PortfolioController(PortfolioService portfolioService) {
    this.portfolioService = portfolioService;
  }

  @GetMapping("/summary")
  public PortfolioSummaryResponse summary() {
    return portfolioService.summary();
  }
}
