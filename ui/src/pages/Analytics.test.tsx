import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import Analytics from "./Analytics";
import { server } from "@/test/mockServer";
import { useAlertStore } from "@/stores/alertStore";

beforeEach(() => {
  useAlertStore.getState().clear();
  server.use(
    http.get("/api/tca", () => HttpResponse.json([])),
    http.get("/api/analytics/pnl/attribution", () => HttpResponse.json({ daily: [] })),
    http.get("/api/analytics/flow/toxicity", () =>
      HttpResponse.json({ rows: [], thresholdBps: 5, horizonsSeconds: [60, 300, 1800] }),
    ),
  );
});

describe("Analytics page", () => {
  it("renders TCA rows on tca tab", async () => {
    server.use(
      http.get("/api/tca", () =>
        HttpResponse.json([
          {
            tcaId: "00000000-0000-0000-0000-000000000001",
            orderId: "11111111-1111-1111-1111-111111111111",
            symbol: "AAPL",
            strategy: "VWAP",
            side: "BUY",
            quantity: 100,
            slippageBps: 1.5,
            implShortfallBps: 1.8,
            vwapBenchmarkBps: 0.5,
            spreadCostBps: 2.0,
            computedAt: "2026-05-31T12:00:00Z",
          },
        ]),
      ),
    );
    render(
      <MemoryRouter>
        <Analytics />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText(/AAPL/)).toBeInTheDocument();
    });
    expect(screen.getByText(/1.50 bps/)).toBeInTheDocument();
  });

  it("loads PnL attribution when pnl tab clicked", async () => {
    server.use(
      http.get("/api/analytics/pnl/attribution", () =>
        HttpResponse.json({
          daily: [
            {
              strategy: "VWAP",
              date: "2026-05-31",
              orders: 3,
              spreadUsd: -45.2,
              timingUsd: 0,
              marketUsd: 120.5,
              commissionUsd: -2.1,
              residualUsd: 0.0,
              realizedPnlUsd: 73.2,
            },
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Analytics />
      </MemoryRouter>,
    );
    await user.click(screen.getByTestId("tab-pnl"));
    await waitFor(() => {
      expect(screen.getByTestId("pnl-table")).toBeInTheDocument();
    });
    expect(screen.getByText("2026-05-31")).toBeInTheDocument();
    expect(screen.getAllByText(/VWAP/).length).toBeGreaterThan(0);
  });

  it("shows alerts badge when alert store has entries", () => {
    useAlertStore.getState().push({
      symbol: "AAPL",
      alertType: "FLOW_TOXICITY",
      severity: "HIGH",
      message: "Markout > threshold",
      timestamp: new Date().toISOString(),
    });
    render(
      <MemoryRouter>
        <Analytics />
      </MemoryRouter>,
    );
    expect(screen.getByTestId("alerts-badge")).toHaveTextContent("1");
  });

  it("renders toxicity table with threshold header", async () => {
    server.use(
      http.get("/api/analytics/flow/toxicity", () =>
        HttpResponse.json({
          rows: [
            {
              strategy: "VWAP",
              horizonSeconds: 300,
              meanMarkoutBps: 7.2,
              stdevMarkoutBps: 2.1,
              observations: 12,
              toxic: true,
            },
          ],
          thresholdBps: 5,
          horizonsSeconds: [60, 300, 1800],
        }),
      ),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Analytics />
      </MemoryRouter>,
    );
    await user.click(screen.getByTestId("tab-toxicity"));
    await waitFor(() => {
      expect(screen.getByTestId("toxicity-section")).toBeInTheDocument();
    });
    expect(screen.getByText(/TOXIC/)).toBeInTheDocument();
    expect(screen.getByText(/threshold 5 bps/)).toBeInTheDocument();
  });
});
