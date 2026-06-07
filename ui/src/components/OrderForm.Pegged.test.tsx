import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import OrderForm from "./OrderForm";
import { server } from "@/test/mockServer";

describe("OrderForm — PEGGED", () => {
  it("shows pegType / pegOffsetBps / priceCap fields for PEGGED order type only", async () => {
    const user = userEvent.setup();
    render(<OrderForm onSubmitted={vi.fn()} />);

    expect(screen.queryByLabelText(/peg type/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/peg offset/i)).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText(/order type/i), "PEGGED");

    expect(screen.getByLabelText(/peg type/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/peg offset/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/price cap/i)).toBeInTheDocument();
  });

  it("submits a PEGGED MIDPOINT order with offset=0 by default", async () => {
    const submittedBody = vi.fn();
    server.use(
      http.post("/api/execution/orders", async ({ request }) => {
        submittedBody(await request.json());
        return HttpResponse.json(
          { orderId: "peg-1", status: "SUBMITTED", submittedAt: "2026-05-20T10:00:00Z" },
          { status: 202 },
        );
      }),
    );

    const onSubmitted = vi.fn();
    const user = userEvent.setup();
    render(<OrderForm onSubmitted={onSubmitted} />);

    await user.type(screen.getByLabelText(/symbol/i), "aapl");
    await user.selectOptions(screen.getByLabelText(/order type/i), "PEGGED");
    await user.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(onSubmitted).toHaveBeenCalled();
    });
    expect(submittedBody).toHaveBeenCalledWith(
      expect.objectContaining({
        symbol: "AAPL",
        orderType: "PEGGED",
        pegType: "MIDPOINT",
        pegOffsetBps: 0,
        quantity: 100,
      }),
    );
  });

  it("submits a PEGGED PRIMARY sell with a positive offset and price cap", async () => {
    const submittedBody = vi.fn();
    server.use(
      http.post("/api/execution/orders", async ({ request }) => {
        submittedBody(await request.json());
        return HttpResponse.json(
          { orderId: "peg-2", status: "SUBMITTED", submittedAt: "2026-05-20T10:00:00Z" },
          { status: 202 },
        );
      }),
    );

    const onSubmitted = vi.fn();
    const user = userEvent.setup();
    render(<OrderForm onSubmitted={onSubmitted} />);

    await user.type(screen.getByLabelText(/symbol/i), "msft");
    await user.selectOptions(screen.getByLabelText(/side/i), "SELL");
    await user.selectOptions(screen.getByLabelText(/order type/i), "PEGGED");
    await user.selectOptions(screen.getByLabelText(/peg type/i), "PRIMARY");

    const offsetInput = screen.getByLabelText(/peg offset/i);
    await user.clear(offsetInput);
    await user.type(offsetInput, "5");

    const capInput = screen.getByLabelText(/price cap/i);
    await user.type(capInput, "400");

    await user.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(onSubmitted).toHaveBeenCalled();
    });
    expect(submittedBody).toHaveBeenCalledWith(
      expect.objectContaining({
        symbol: "MSFT",
        side: "SELL",
        orderType: "PEGGED",
        pegType: "PRIMARY",
        pegOffsetBps: 5,
        limitPrice: 400,
      }),
    );
  });

  it("surfaces API errors on PEGGED submit failure", async () => {
    server.use(
      http.post("/api/execution/orders", () =>
        HttpResponse.text("pegType is required", { status: 400 }),
      ),
    );

    const onSubmitted = vi.fn();
    const user = userEvent.setup();
    render(<OrderForm onSubmitted={onSubmitted} />);
    await user.type(screen.getByLabelText(/symbol/i), "aapl");
    await user.selectOptions(screen.getByLabelText(/order type/i), "PEGGED");
    await user.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(screen.getByText(/pegType is required/i)).toBeInTheDocument();
    });
    expect(onSubmitted).not.toHaveBeenCalled();
  });
});
