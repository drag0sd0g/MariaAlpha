import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect, beforeEach } from "vitest";
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
          },
        ]),
      ),
      http.get("/api/recon/summary", () =>
        HttpResponse.json({
          reconDate: "2026-05-31",
          totalBreaks: 1,
          bySeverity: { HIGH: 1 },
          byBreakType: { MISSING_FILL: 1 },
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
});
