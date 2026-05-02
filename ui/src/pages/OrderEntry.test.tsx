import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect } from "vitest";
import OrderEntry from "./OrderEntry";
import { server } from "@/test/mockServer";
import { http, HttpResponse } from "msw";

describe("OrderEntry", () => {
  it("submits a valid LIMIT BUY", async () => {
    server.use(
      http.get("/api/orders", () => HttpResponse.json([])),
      http.post("/api/execution/orders", async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        expect(body.symbol).toBe("AAPL");
        expect(body.side).toBe("BUY");
        expect(body.orderType).toBe("LIMIT");
        expect(body.quantity).toBe(100);
        expect(body.limitPrice).toBe(178.5);
        return HttpResponse.json(
          { orderId: "abc", status: "SUBMITTED", acceptedAt: "2026-05-02T10:00:00Z" },
          { status: 202 },
        );
      }),
    );

    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <OrderEntry />
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText(/symbol/i), "aapl");
    await user.clear(screen.getByLabelText(/quantity/i));
    await user.type(screen.getByLabelText(/quantity/i), "100");
    await user.type(screen.getByLabelText(/limit price/i), "178.50");
    await user.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(screen.queryByRole("button", { name: /submitting/i })).toBeNull();
    });
  });

  it("rejects LIMIT order without price", async () => {
    server.use(http.get("/api/orders", () => HttpResponse.json([])));
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <OrderEntry />
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText(/symbol/i), "AAPL");
    await user.click(screen.getByRole("button", { name: /submit/i }));
    expect(await screen.findByText(/LIMIT orders need a price/i)).toBeInTheDocument();
  });
});
