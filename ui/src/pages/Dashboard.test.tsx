import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect } from "vitest";
import Dashboard from "./Dashboard";
import { server } from "@/test/mockServer";
import { http, HttpResponse } from "msw";

describe("Dashboard", () => {
  it("renders summary cards from /api/portfolio/summary", async () => {
    server.use(
      http.get("/api/portfolio/summary", () =>
        HttpResponse.json({
          totalValue: 100000,
          cashBalance: 50000,
          grossExposure: 60000,
          netExposure: 40000,
          realizedPnl: 100,
          unrealizedPnl: 200,
          totalPnl: 300,
          openPositions: 2,
          asOf: "2026-05-02T10:00:00Z",
        }),
      ),
      http.get("/api/positions", () => HttpResponse.json([])),
    );

    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText("$100,000.00")).toBeInTheDocument());
    expect(screen.getByText("$300.00")).toBeInTheDocument();
  });

  it("displays an error banner when the API fails", async () => {
    server.use(
      http.get("/api/portfolio/summary", () => HttpResponse.text("boom", { status: 500 })),
    );
    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText(/boom/i)).toBeInTheDocument());
  });
});
