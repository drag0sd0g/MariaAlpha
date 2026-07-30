import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import Portfolio from "./Portfolio";
import { server } from "@/test/mockServer";

const STATE = {
  positions: [
    {
      symbol: "NVDA",
      quantity: 2000,
      avgEntryPrice: 500,
      markPrice: 500,
      markSource: "market-data",
      notionalUsd: 1_000_000,
      realizedPnl: 0,
      unrealizedPnl: 0,
      updatedAt: null,
    },
    {
      symbol: "MSFT",
      quantity: -2415,
      avgEntryPrice: 414,
      markPrice: 414,
      markSource: "market-data",
      notionalUsd: -1_000_000,
      realizedPnl: 0,
      unrealizedPnl: 0,
      updatedAt: null,
    },
  ],
  navUsd: 3_000_000,
  baseNavUsd: 1_000_000,
  grossExposureUsd: 2_000_000,
  netExposureUsd: 0,
  navSource: "config-cash + marked-positions",
  positionCount: 2,
};

const OPTIMIZE = {
  objective: "RISK_PARITY",
  symbols: ["NVDA", "MSFT"],
  weights: { NVDA: 0.4, MSFT: 0.6 },
  expectedReturn: 0.09,
  volatility: 0.22,
  sharpe: 0.409,
  riskContributions: { NVDA: 0.11, MSFT: 0.11 },
  diversificationRatio: 1.42,
  effectiveN: 1.92,
  converged: true,
  iterations: 12,
  message: "Optimization terminated successfully",
  expectedReturnSource: "black-litterman-equilibrium",
};

const RISK = {
  asOf: "2026-07-29T14:00:00Z",
  symbols: ["MSFT", "NVDA"],
  navUsd: 3_000_000,
  grossExposureUsd: 2_000_000,
  netExposureUsd: 0,
  confidence: 0.95,
  horizonDays: 1,
  parametric: {
    method: "PARAMETRIC",
    confidence: 0.95,
    horizonDays: 1,
    varUsd: 41_747.75,
    expectedShortfallUsd: 52_331.1,
    portfolioVolatilityUsd: 25_380.2,
    observations: null,
    simulations: null,
    sufficient: true,
    notes: [],
  },
  historical: {
    method: "HISTORICAL",
    confidence: 0.95,
    horizonDays: 1,
    varUsd: 0,
    expectedShortfallUsd: 0,
    portfolioVolatilityUsd: 0,
    observations: 3,
    simulations: null,
    sufficient: false,
    notes: ["only 3 observations"],
  },
  monteCarlo: {
    method: "MONTE_CARLO",
    confidence: 0.95,
    horizonDays: 1,
    varUsd: 41_870.36,
    expectedShortfallUsd: 52_900,
    portfolioVolatilityUsd: 25_400,
    observations: null,
    simulations: 10_000,
    sufficient: true,
    notes: [],
  },
  components: [],
  diversificationRatio: 1.787,
  sumOfAbsolutesVarUsd: 74_598.82,
  varLimitUsd: 750_000,
  breachesVarLimit: false,
};

const COMPONENTS = {
  rows: [
    {
      symbol: "NVDA",
      notionalUsd: 1_000_000,
      standaloneVarUsd: 49_732,
      marginalVarUsd: 0.043,
      componentVarUsd: 43_096.2,
      pctOfTotal: 1.032,
    },
    {
      symbol: "MSFT",
      notionalUsd: -1_000_000,
      standaloneVarUsd: 24_866,
      marginalVarUsd: 0.00135,
      componentVarUsd: -1348.5,
      pctOfTotal: -0.032,
    },
  ],
};

const REBALANCE = {
  symbols: ["NVDA", "MSFT"],
  legs: [
    {
      symbol: "NVDA",
      side: "SELL" as const,
      currentShares: 2000,
      targetShares: 917,
      deltaShares: 1083,
      notionalUsd: 541_500,
      linearCostUsd: 81.2,
      impactCostUsd: 12.4,
      totalCostUsd: 93.6,
    },
  ],
  suppressedLegs: [
    {
      symbol: "MSFT",
      side: "BUY" as const,
      currentShares: -2415,
      targetShares: -2410,
      deltaShares: 5,
      notionalUsd: 2070,
      linearCostUsd: 0.3,
      impactCostUsd: 0.01,
      totalCostUsd: 0.31,
    },
  ],
  currentWeights: { NVDA: 0.33, MSFT: -0.33 },
  targetWeights: { NVDA: 0.15, MSFT: -0.33 },
  navUsd: 3_000_000,
  turnoverPct: 0.09,
  estimatedCostUsd: 93.6,
  expectedUtilityGain: 0.004,
  converged: true,
  message: "ok",
  basketOrderRequest: {
    name: "rebalance-test",
    legs: [{ symbol: "NVDA", side: "SELL", orderType: "MARKET", quantity: 1083, tif: "DAY" }],
  },
  submitEnabled: false,
};

const FACTORS = {
  symbols: ["MSFT", "NVDA"],
  factors: ["MARKET", "SIZE", "VOLATILITY", "SECTOR_TECH"],
  exposures: { MARKET: 1.3, SIZE: -0.2, VOLATILITY: 0.5, SECTOR_TECH: 1.0 },
  varianceContributions: { MARKET: 0.04, SIZE: 0.001, VOLATILITY: 0.002, SECTOR_TECH: 0.01 },
  systematicVariance: 0.053,
  idiosyncraticVariance: 0.0,
  modelVariance: 0.053,
  covarianceVariance: 0.053,
  modelFit: 1.0,
  systematicVariancePct: 1.0,
  idiosyncraticVariancePct: 0.0,
  modelVolatility: 0.23,
  covarianceVolatility: 0.23,
  pca: {
    varianceExplained: [0.72, 0.28],
    cumulativeVarianceExplained: [0.72, 1.0],
    portfolioLoadings: [0.3, 0.1],
    topComponentSymbols: [{ symbol: "NVDA", loading: 0.71 }],
  },
  notes: ["the model is saturated: 4 factors span 2 assets"],
};

const STRESS = {
  navUsd: 3_000_000,
  lossLimitUsd: 1_500_000,
  scenarios: [
    {
      name: "COVID_20200316",
      description: "S&P -11.98% single day",
      pnlUsd: -186_962,
      pnlPctOfNav: -0.062,
      worstSymbol: "NVDA",
      worstSymbolPnlUsd: -197_670,
      breachesLimit: false,
    },
    {
      name: "AI_UNWIND",
      description: "Crowded AI trade unwinds",
      pnlUsd: -1_800_000,
      pnlPctOfNav: -0.6,
      worstSymbol: "NVDA",
      worstSymbolPnlUsd: -250_000,
      breachesLimit: true,
    },
  ],
};

beforeEach(() => {
  server.use(
    http.get("/api/analytics/portfolio/state", () => HttpResponse.json(STATE)),
    http.post("/api/analytics/portfolio/optimize", () => HttpResponse.json(OPTIMIZE)),
    http.post("/api/analytics/portfolio/efficient-frontier", () =>
      HttpResponse.json({ symbols: ["NVDA"], points: [], expectedReturnSource: "supplied" }),
    ),
    http.get("/api/analytics/risk/var", () => HttpResponse.json(RISK)),
    http.get("/api/analytics/risk/components", () => HttpResponse.json(COMPONENTS)),
    http.get("/api/analytics/portfolio/factors", () => HttpResponse.json(FACTORS)),
    http.get("/api/analytics/risk/stress", () => HttpResponse.json(STRESS)),
    http.post("/api/analytics/portfolio/rebalance", () => HttpResponse.json(REBALANCE)),
  );
});

function renderPage() {
  return render(
    <MemoryRouter>
      <Portfolio />
    </MemoryRouter>,
  );
}

describe("Portfolio page", () => {
  it("renders the portfolio state tiles", async () => {
    renderPage();
    expect(await screen.findByText("$3,000,000")).toBeInTheDocument();
    expect(screen.getByText("config-cash + marked-positions")).toBeInTheDocument();
  });

  it("renders optimizer weights and risk contributions", async () => {
    renderPage();
    const table = await screen.findByTestId("risk-contributions");
    expect(within(table).getByText("NVDA")).toBeInTheDocument();
    expect(within(table).getByText("40.00%")).toBeInTheDocument();
    expect(within(table).getByText("60.00%")).toBeInTheDocument();
  });

  it("shows a warning when the solver did not converge", async () => {
    server.use(
      http.post("/api/analytics/portfolio/optimize", () =>
        HttpResponse.json({ ...OPTIMIZE, converged: false, message: "iteration limit" }),
      ),
    );
    renderPage();
    expect(await screen.findByTestId("not-converged")).toHaveTextContent("iteration limit");
  });

  it("shows the diversification callout against the sum-of-absolutes number", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId("tab-risk"));

    const callout = await screen.findByTestId("diversification-callout");
    expect(callout).toHaveTextContent("1.79x");
    expect(callout).toHaveTextContent("$74,599");
    expect(callout).toHaveTextContent("$41,748");
  });

  it("renders a negative component VaR for the hedge", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId("tab-risk"));

    const table = await screen.findByTestId("component-var");
    const msftRow = within(table).getByText("MSFT").closest("tr");
    expect(msftRow).not.toBeNull();
    expect(msftRow).toHaveTextContent("-$1,349");
  });

  it("renders factor exposures and surfaces model notes", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId("tab-factors"));

    expect(await screen.findByTestId("factor-notes")).toHaveTextContent("saturated");
  });

  it("flags a stress scenario that breaches the limit", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId("tab-stress"));

    const rows = await screen.findByTestId("stress-rows");
    const breachRow = within(rows).getByText("AI_UNWIND").closest("tr");
    expect(breachRow).not.toBeNull();
    expect(within(breachRow as HTMLElement).getByText("BREACH")).toBeInTheDocument();
  });

  it("disables the basket button when submission is off", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId("tab-rebalance"));

    const button = await screen.findByTestId("send-basket");
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("title", expect.stringContaining("disabled"));
  });

  it("enables the basket button when the backend allows submission", async () => {
    server.use(
      http.post("/api/analytics/portfolio/rebalance", () =>
        HttpResponse.json({ ...REBALANCE, submitEnabled: true }),
      ),
    );
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId("tab-rebalance"));

    expect(await screen.findByTestId("send-basket")).toBeEnabled();
  });

  it("lists the trade legs and the suppressed ones separately", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId("tab-rebalance"));

    const legs = await screen.findByTestId("rebalance-legs");
    expect(within(legs).getByText("NVDA")).toBeInTheDocument();
    expect(within(legs).getByText("1083")).toBeInTheDocument();
    expect(screen.getByText(/1 leg\(s\) suppressed/)).toBeInTheDocument();
  });

  it("surfaces an API error", async () => {
    server.use(
      http.post("/api/analytics/portfolio/optimize", () =>
        HttpResponse.json({ detail: "max_weight too small" }, { status: 400 }),
      ),
    );
    renderPage();
    await waitFor(async () => {
      expect(await screen.findByTestId("portfolio-error")).toHaveTextContent("max_weight");
    });
  });

  it("re-requests the optimizer when the objective changes", async () => {
    const seen: string[] = [];
    server.use(
      http.post("/api/analytics/portfolio/optimize", async ({ request }) => {
        const body = (await request.json()) as { objective: string };
        seen.push(body.objective);
        return HttpResponse.json({ ...OPTIMIZE, objective: body.objective });
      }),
    );
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("risk-contributions");

    await user.selectOptions(screen.getByTestId("objective-select"), "MAX_SHARPE");
    await waitFor(() => {
      expect(seen).toContain("MAX_SHARPE");
    });
  });
});
