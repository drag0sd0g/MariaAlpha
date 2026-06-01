import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { http, HttpResponse } from "msw";
import Reconciliation from "./Reconciliation";
import { server } from "@/test/mockServer";

beforeEach(() => {
  server.use(
    http.get("/api/recon/breaks", () => HttpResponse.json([])),
    http.get("/api/recon/summary", () =>
      HttpResponse.json({
        reconDate: "2026-05-31",
        totalBreaks: 0,
        bySeverity: {},
        byBreakType: {},
      }),
    ),
    http.get("/api/recon/runs", () => HttpResponse.json([])),
  );
});

describe("Reconciliation page", () => {
  it("renders empty state when no breaks are recorded", async () => {
    render(
      <MemoryRouter>
        <Reconciliation />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText(/No reconciliation breaks recorded/)).toBeInTheDocument();
    });
    expect(screen.getByTestId("card-Total breaks")).toHaveTextContent("0");
    // No run record yet → Matched falls back to em-dash
    expect(screen.getByTestId("card-Matched")).toHaveTextContent("—");
  });

  it("renders break rows + summary cards when data present", async () => {
    server.use(
      http.get("/api/recon/breaks", () =>
        HttpResponse.json([
          {
            breakId: "00000000-0000-0000-0000-000000000001",
            reconDate: "2026-05-31",
            orderId: "11111111-1111-1111-1111-111111111111",
            breakType: "MISSING_FILL",
            severity: "HIGH",
            resolution: "PENDING",
            symbol: "AAPL",
            description: "External fill not matched internally",
          },
        ]),
      ),
      http.get("/api/recon/summary", () =>
        HttpResponse.json({
          reconDate: "2026-05-31",
          totalBreaks: 1,
          bySeverity: { HIGH: 1 },
          byBreakType: { MISSING_FILL: 1 },
          run: {
            runId: "aa",
            reconDate: "2026-05-31",
            status: "SUCCESS",
            source: "SCHEDULED",
            startedAt: "2026-05-31T22:00:00Z",
            finishedAt: "2026-05-31T22:00:05Z",
            internalFillsCount: 0,
            externalFillsCount: 1,
            breaksCount: 1,
          },
        }),
      ),
    );
    render(
      <MemoryRouter>
        <Reconciliation />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getAllByText("MISSING_FILL").length).toBeGreaterThan(0);
    });
    expect(screen.getByTestId("card-Total breaks")).toHaveTextContent("1");
    expect(screen.getByTestId("card-High")).toHaveTextContent("1");
    expect(screen.getByTestId("recon-bytype-table")).toBeInTheDocument();
    expect(screen.getByTestId("recon-run-status")).toHaveTextContent("SUCCESS");
    // externalFillsCount=1, breaks=1 → matched=0
    expect(screen.getByTestId("card-Matched")).toHaveTextContent("0");
    // Description column is wired through
    expect(screen.getByText(/External fill not matched internally/)).toBeInTheDocument();
  });

  it("shows error banner when the API errors", async () => {
    server.use(http.get("/api/recon/breaks", () => HttpResponse.text("boom", { status: 500 })));
    render(
      <MemoryRouter>
        <Reconciliation />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByTestId("recon-error")).toBeInTheDocument();
    });
  });

  it("trigger button calls POST /api/recon/run and refreshes the summary", async () => {
    const handler = vi.fn(() =>
      HttpResponse.json({
        runId: "newrun",
        reconDate: "2026-05-31",
        status: "SUCCESS",
        source: "MANUAL",
        startedAt: "2026-05-31T22:00:00Z",
        finishedAt: "2026-05-31T22:00:05Z",
        internalFillsCount: 5,
        externalFillsCount: 5,
        breaksCount: 0,
      }),
    );
    server.use(http.post("/api/recon/run", handler));
    render(
      <MemoryRouter>
        <Reconciliation />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByTestId("recon-trigger")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("recon-trigger"));

    await waitFor(() => {
      expect(handler).toHaveBeenCalled();
    });
  });

  it("renders failed run errorMessage", async () => {
    server.use(
      http.get("/api/recon/summary", () =>
        HttpResponse.json({
          reconDate: "2026-05-31",
          totalBreaks: 0,
          bySeverity: {},
          byBreakType: {},
          run: {
            runId: "fail",
            reconDate: "2026-05-31",
            status: "FAILED",
            source: "SCHEDULED",
            startedAt: "2026-05-31T22:00:00Z",
            finishedAt: "2026-05-31T22:00:02Z",
            errorMessage: "Alpaca 503",
          },
        }),
      ),
    );
    render(
      <MemoryRouter>
        <Reconciliation />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByTestId("recon-run-status")).toHaveTextContent("FAILED");
    });
    expect(screen.getByTestId("recon-run-error")).toHaveTextContent("Alpaca 503");
  });
});
