import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import Strategies from "./Strategies";
import { server } from "@/test/mockServer";

describe("Strategies page", () => {
  it("renders rows with active strategy + ML signal + regime", async () => {
    server.use(
      http.get("/api/strategies", () => HttpResponse.json(["VWAP", "TWAP", "MOMENTUM"])),
      http.get("/api/strategies/state", () =>
        HttpResponse.json([
          {
            symbol: "AAPL",
            activeStrategy: "VWAP",
            mlSignal: { direction: "LONG", confidence: 0.83 },
            mlRegime: { regime: "TRENDING_UP", confidence: 0.71 },
          },
        ]),
      ),
    );
    render(
      <MemoryRouter>
        <Strategies />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByTestId("row-AAPL")).toBeInTheDocument();
    });
    const row = screen.getByTestId("row-AAPL");
    // "VWAP" appears in the active-strategy cell + the switch <select> option list
    expect(within(row).getAllByText("VWAP").length).toBeGreaterThan(0);
    expect(within(row).getByText(/LONG/)).toBeInTheDocument();
    expect(within(row).getByText(/TRENDING_UP/)).toBeInTheDocument();
  });

  it("PUTs strategy binding when Bind clicked", async () => {
    let received: { strategyName: string } | null = null;
    server.use(
      http.get("/api/strategies", () => HttpResponse.json(["VWAP", "TWAP"])),
      http.get("/api/strategies/state", () => HttpResponse.json([])),
      http.put("/api/strategies/MSFT", async ({ request }) => {
        received = (await request.json()) as { strategyName: string };
        return new HttpResponse(null, { status: 200 });
      }),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Strategies />
      </MemoryRouter>,
    );
    // Wait for default-symbol rows to appear (the merge with DEFAULT_SYMBOLS)
    await waitFor(() => {
      expect(screen.getByTestId("row-MSFT")).toBeInTheDocument();
    });
    await user.type(screen.getByTestId("bind-symbol"), "MSFT");
    await user.click(screen.getByTestId("bind-submit"));
    await waitFor(() => {
      expect(received).not.toBeNull();
    });
    expect(received!.strategyName).toBe("VWAP");
  });

  it("surfaces ML-unavailable rows with em-dashes", async () => {
    server.use(
      http.get("/api/strategies", () => HttpResponse.json(["VWAP"])),
      http.get("/api/strategies/state", () =>
        HttpResponse.json([{ symbol: "TSLA", activeStrategy: "VWAP" }]),
      ),
    );
    render(
      <MemoryRouter>
        <Strategies />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByTestId("row-TSLA")).toBeInTheDocument();
    });
    const row = screen.getByTestId("row-TSLA");
    // 4 cells with em-dash content: strategy filled, signal empty, regime empty
    expect(within(row).getAllByText("—").length).toBeGreaterThanOrEqual(2);
  });
});
