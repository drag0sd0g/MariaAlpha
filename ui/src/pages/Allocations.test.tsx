import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import Allocations from "./Allocations";
import { server } from "@/test/mockServer";

const roster = {
  defaultMethod: "PRO_RATA",
  subAccounts: [
    { name: "HOUSE", weight: 50.0 },
    { name: "HEDGE_FUND_A", weight: 30.0 },
    { name: "HEDGE_FUND_B", weight: 20.0 },
  ],
};

describe("Allocations page", () => {
  it("loads the sub-account roster on mount", async () => {
    server.use(http.get("/api/allocations/sub-accounts", () => HttpResponse.json(roster)));
    render(
      <MemoryRouter>
        <Allocations />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByTestId("alloc-roster")).toBeInTheDocument();
    });
    expect(screen.getByText("HOUSE")).toBeInTheDocument();
    expect(screen.getByText("HEDGE_FUND_A")).toBeInTheDocument();
  });

  it("runs an allocation and renders the per-sub-account split", async () => {
    let submitted: Record<string, unknown> | null = null;
    server.use(
      http.get("/api/allocations/sub-accounts", () => HttpResponse.json(roster)),
      http.post("/api/allocations/run", async ({ request }) => {
        submitted = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          [
            {
              allocationId: "a-1",
              orderId: "00000000-0000-0000-0000-000000000001",
              subAccount: "HOUSE",
              symbol: "AAPL",
              side: "BUY",
              allocatedQuantity: "500",
              allocatedAvgPrice: "178.42",
              allocationMethod: "PRO_RATA",
              parentFilledQuantity: "1000",
              parentAvgPrice: "178.42",
              allocatedAt: "2026-05-31T15:00:00Z",
            },
            {
              allocationId: "a-2",
              orderId: "00000000-0000-0000-0000-000000000001",
              subAccount: "HEDGE_FUND_A",
              symbol: "AAPL",
              side: "BUY",
              allocatedQuantity: "300",
              allocatedAvgPrice: "178.42",
              allocationMethod: "PRO_RATA",
              parentFilledQuantity: "1000",
              parentAvgPrice: "178.42",
              allocatedAt: "2026-05-31T15:00:00Z",
            },
            {
              allocationId: "a-3",
              orderId: "00000000-0000-0000-0000-000000000001",
              subAccount: "HEDGE_FUND_B",
              symbol: "AAPL",
              side: "BUY",
              allocatedQuantity: "200",
              allocatedAvgPrice: "178.42",
              allocationMethod: "PRO_RATA",
              parentFilledQuantity: "1000",
              parentAvgPrice: "178.42",
              allocatedAt: "2026-05-31T15:00:00Z",
            },
          ],
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Allocations />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByTestId("alloc-roster")).toBeInTheDocument();
    });
    await user.click(screen.getByTestId("alloc-run"));
    await waitFor(() => {
      expect(screen.getByTestId("alloc-results")).toBeInTheDocument();
    });
    expect(screen.getByText("500")).toBeInTheDocument();
    expect(screen.getByText("300")).toBeInTheDocument();
    expect(screen.getByText("200")).toBeInTheDocument();
    expect(submitted).not.toBeNull();
    expect(submitted!.symbol).toBe("AAPL");
    expect(submitted!.parentFilledQuantity).toBe(1000);
  });

  it("surfaces API errors", async () => {
    server.use(
      http.get("/api/allocations/sub-accounts", () => HttpResponse.json(roster)),
      http.post("/api/allocations/run", () =>
        HttpResponse.text("parentFilledQuantity must be > 0", { status: 400 }),
      ),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Allocations />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByTestId("alloc-roster")).toBeInTheDocument();
    });
    await user.click(screen.getByTestId("alloc-run"));
    await waitFor(() => {
      expect(screen.getByTestId("alloc-error")).toBeInTheDocument();
    });
  });
});
