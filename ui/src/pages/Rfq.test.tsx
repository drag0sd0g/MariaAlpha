import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import Rfq from "./Rfq";
import { server } from "@/test/mockServer";

const sampleQuote = {
  quoteId: "00000000-0000-0000-0000-000000000001",
  symbol: "AAPL",
  quantity: 100,
  marketMid: 178.5,
  adjustedMid: 178.42,
  bid: 178.38,
  ask: 178.46,
  breakdown: {
    inventoryNetQuantity: 1000,
    inventoryNotionalUsd: 178_500,
    inventorySkewBps: 4.5,
    realizedVolBps: 15.2,
    volWideningBps: 7.6,
    advParticipationFraction: 0.000002,
    advWideningBps: 0.006,
    baseHalfSpreadBps: 2.0,
    totalHalfSpreadBps: 9.61,
    advShares: 60_000_000,
  },
  issuedAt: "2026-05-31T15:00:00Z",
  expiresAt: "2026-05-31T15:00:10Z",
  validForMs: 10_000,
};

describe("Rfq page", () => {
  it("requests a quote and renders bid/ask + breakdown", async () => {
    server.use(
      http.post("/api/rfq/quote", async ({ request }) => {
        const body = (await request.json()) as { symbol: string; quantity: number };
        expect(body.symbol).toBe("AAPL");
        expect(body.quantity).toBe(100);
        return HttpResponse.json(sampleQuote);
      }),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Rfq />
      </MemoryRouter>,
    );
    await user.click(screen.getByTestId("rfq-request"));
    await waitFor(() => {
      expect(screen.getByTestId("rfq-bid").textContent).toBe("178.3800");
    });
    expect(screen.getByTestId("rfq-ask").textContent).toBe("178.4600");
    expect(screen.getByTestId("rfq-total-bps").textContent).toBe("9.610 bps");
  });

  it("publishes order signal when ask side is accepted", async () => {
    let accepted: { quoteId: string; side: string; price: number } | null = null;
    server.use(
      http.post("/api/rfq/quote", () => HttpResponse.json(sampleQuote)),
      http.post("/api/rfq/accept", async ({ request }) => {
        accepted = (await request.json()) as { quoteId: string; side: string; price: number };
        return HttpResponse.json({
          quoteId: sampleQuote.quoteId,
          symbol: "AAPL",
          status: "ACCEPTED",
          signal: { side: "BUY" },
        });
      }),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Rfq />
      </MemoryRouter>,
    );
    await user.click(screen.getByTestId("rfq-request"));
    await waitFor(() => {
      expect(screen.getByTestId("rfq-accept-buy")).toBeInTheDocument();
    });
    await user.click(screen.getByTestId("rfq-accept-buy"));
    await waitFor(() => {
      expect(screen.getByTestId("rfq-accepted")).toBeInTheDocument();
    });
    expect(accepted).not.toBeNull();
    expect(accepted!.side).toBe("BUY");
    expect(accepted!.price).toBe(sampleQuote.ask);
  });

  it("surfaces API errors", async () => {
    server.use(http.post("/api/rfq/quote", () => HttpResponse.text("no book", { status: 503 })));
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Rfq />
      </MemoryRouter>,
    );
    await user.click(screen.getByTestId("rfq-request"));
    await waitFor(() => {
      expect(screen.getByTestId("rfq-error")).toBeInTheDocument();
    });
  });
});
