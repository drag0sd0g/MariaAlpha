import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import OrderForm from "./OrderForm";
import { server } from "@/test/mockServer";

describe("OrderForm — GTC / ICEBERG", () => {
  it("submits a GTC order with tif=GTC and shows intrinsic TIF hint", async () => {
    const submittedBody = vi.fn();
    server.use(
      http.post("/api/execution/orders", async ({ request }) => {
        submittedBody(await request.json());
        return HttpResponse.json(
          { orderId: "gtc-1", status: "SUBMITTED", submittedAt: "2026-05-20T10:00:00Z" },
          { status: 202 },
        );
      }),
    );

    const onSubmitted = vi.fn();
    const user = userEvent.setup();
    render(<OrderForm onSubmitted={onSubmitted} />);

    await user.type(screen.getByLabelText(/symbol/i), "aapl");
    await user.selectOptions(screen.getByLabelText(/order type/i), "GTC");
    await user.type(screen.getByLabelText(/limit price/i), "150.50");

    expect(screen.getByText(/time-in-force: GTC \(intrinsic to GTC\)/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(onSubmitted).toHaveBeenCalled();
    });
    expect(submittedBody).toHaveBeenCalledWith(
      expect.objectContaining({
        symbol: "AAPL",
        orderType: "GTC",
        tif: "GTC",
        quantity: 100,
        limitPrice: 150.5,
      }),
    );
  });

  it("shows display quantity input for ICEBERG order type only", async () => {
    const user = userEvent.setup();
    render(<OrderForm onSubmitted={vi.fn()} />);

    expect(screen.queryByLabelText(/display quantity/i)).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText(/order type/i), "ICEBERG");

    expect(screen.getByLabelText(/display quantity/i)).toBeInTheDocument();
  });

  it("submits an ICEBERG order with displayQuantity", async () => {
    const submittedBody = vi.fn();
    server.use(
      http.post("/api/execution/orders", async ({ request }) => {
        submittedBody(await request.json());
        return HttpResponse.json(
          { orderId: "ice-1", status: "SUBMITTED", submittedAt: "2026-05-20T10:00:00Z" },
          { status: 202 },
        );
      }),
    );

    const onSubmitted = vi.fn();
    const user = userEvent.setup();
    render(<OrderForm onSubmitted={onSubmitted} />);

    await user.type(screen.getByLabelText(/symbol/i), "msft");
    await user.selectOptions(screen.getByLabelText(/order type/i), "ICEBERG");
    await user.clear(screen.getByLabelText(/^quantity$/i));
    await user.type(screen.getByLabelText(/^quantity$/i), "10000");
    await user.type(screen.getByLabelText(/limit price/i), "320.00");
    await user.type(screen.getByLabelText(/display quantity/i), "1000");

    await user.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(onSubmitted).toHaveBeenCalled();
    });
    expect(submittedBody).toHaveBeenCalledWith(
      expect.objectContaining({
        symbol: "MSFT",
        orderType: "ICEBERG",
        quantity: 10000,
        limitPrice: 320,
        displayQuantity: 1000,
      }),
    );
  });

  it("rejects ICEBERG when displayQuantity >= quantity", async () => {
    const user = userEvent.setup();
    render(<OrderForm onSubmitted={vi.fn()} />);

    await user.type(screen.getByLabelText(/symbol/i), "aapl");
    await user.selectOptions(screen.getByLabelText(/order type/i), "ICEBERG");
    await user.type(screen.getByLabelText(/limit price/i), "150");
    await user.type(screen.getByLabelText(/display quantity/i), "150");

    await user.click(screen.getByRole("button", { name: /submit/i }));

    expect(
      await screen.findByText(/displayQuantity must be strictly less than quantity/i),
    ).toBeInTheDocument();
  });
});
