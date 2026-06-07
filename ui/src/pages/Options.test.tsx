import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import Options from "./Options";
import { server } from "@/test/mockServer";

const samplePricing = {
  symbol: "AAPL",
  type: "CALL",
  price: 4.76,
  greeks: {
    delta: 0.7791,
    gamma: 0.0498,
    vega: 0.0879,
    theta: -0.01247,
    rho: 0.1398,
  },
};

const sampleIv = {
  symbol: "AAPL",
  type: "CALL",
  impliedVolatility: 0.2536,
  iterations: 4,
  method: "NEWTON",
  residual: 1.2e-8,
};

describe("Options page", () => {
  it("requests a price and renders Greeks", async () => {
    server.use(
      http.post("/api/options/price", async ({ request }) => {
        const body = (await request.json()) as { symbol: string; type: string };
        expect(body.symbol).toBe("AAPL");
        expect(body.type).toBe("CALL");
        return HttpResponse.json(samplePricing);
      }),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Options />
      </MemoryRouter>,
    );
    await user.click(screen.getByTestId("opt-price-btn"));
    await waitFor(() => {
      expect(screen.getByTestId("opt-price").textContent).toBe("$4.7600");
    });
    expect(screen.getByTestId("opt-delta").textContent).toBe("+0.7791");
    expect(screen.getByTestId("opt-gamma").textContent).toBe("+0.0498");
    expect(screen.getByTestId("opt-vega").textContent).toBe("+0.0879");
    expect(screen.getByTestId("opt-theta").textContent).toBe("-0.0125");
    expect(screen.getByTestId("opt-rho").textContent).toBe("+0.1398");
  });

  it("solves implied vol when a market price is supplied", async () => {
    server.use(
      http.post("/api/options/implied-volatility", async ({ request }) => {
        const body = (await request.json()) as { marketPrice: number };
        expect(body.marketPrice).toBe(5.12);
        return HttpResponse.json(sampleIv);
      }),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Options />
      </MemoryRouter>,
    );
    const marketPriceInput = screen.getByTestId("opt-market-price");
    await user.clear(marketPriceInput);
    await user.type(marketPriceInput, "5.12");
    await user.click(screen.getByTestId("opt-iv-btn"));
    await waitFor(() => {
      expect(screen.getByTestId("opt-iv-value").textContent).toBe("25.36%");
    });
  });

  it("surfaces API errors", async () => {
    server.use(
      http.post("/api/options/price", () =>
        HttpResponse.text("volatility must be > 0", { status: 400 }),
      ),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Options />
      </MemoryRouter>,
    );
    await user.click(screen.getByTestId("opt-price-btn"));
    await waitFor(() => {
      expect(screen.getByTestId("opt-error")).toBeInTheDocument();
    });
  });
});
