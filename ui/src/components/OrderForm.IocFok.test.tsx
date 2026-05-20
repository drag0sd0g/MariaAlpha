import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import OrderForm from "./OrderForm";
import { server } from "@/test/mockServer";

describe("OrderForm — IOC / FOK", () => {
  it("exposes IOC and FOK in the order-type dropdown", () => {
    render(<OrderForm onSubmitted={vi.fn()} />);
    const dropdown = screen.getByLabelText(/order type/i);
    const options = Array.from((dropdown as HTMLSelectElement).options).map(
      (o) => o.value,
    );
    expect(options).toEqual(
      expect.arrayContaining(["MARKET", "LIMIT", "STOP", "IOC", "FOK", "GTC", "ICEBERG"]),
    );
  });

  it("submits an IOC order with tif=IOC and shows intrinsic TIF hint", async () => {
    const submittedBody = vi.fn();
    server.use(
      http.post("/api/execution/orders", async ({ request }) => {
        submittedBody(await request.json());
        return HttpResponse.json(
          { orderId: "ioc-1", status: "SUBMITTED", submittedAt: "2026-05-20T10:00:00Z" },
          { status: 202 },
        );
      }),
    );

    const onSubmitted = vi.fn();
    const user = userEvent.setup();
    render(<OrderForm onSubmitted={onSubmitted} />);

    await user.type(screen.getByLabelText(/symbol/i), "aapl");
    await user.selectOptions(screen.getByLabelText(/order type/i), "IOC");
    await user.type(screen.getByLabelText(/limit price/i), "150.50");

    expect(screen.getByText(/time-in-force: IOC \(intrinsic to IOC\)/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(onSubmitted).toHaveBeenCalled();
    });
    expect(submittedBody).toHaveBeenCalledWith(
      expect.objectContaining({
        symbol: "AAPL",
        orderType: "IOC",
        tif: "IOC",
        quantity: 100,
        limitPrice: 150.5,
      }),
    );
  });

  it("submits a FOK order with tif=FOK and shows intrinsic TIF hint", async () => {
    const submittedBody = vi.fn();
    server.use(
      http.post("/api/execution/orders", async ({ request }) => {
        submittedBody(await request.json());
        return HttpResponse.json(
          { orderId: "fok-1", status: "SUBMITTED", submittedAt: "2026-05-20T10:00:00Z" },
          { status: 202 },
        );
      }),
    );

    const onSubmitted = vi.fn();
    const user = userEvent.setup();
    render(<OrderForm onSubmitted={onSubmitted} />);

    await user.type(screen.getByLabelText(/symbol/i), "msft");
    await user.selectOptions(screen.getByLabelText(/order type/i), "FOK");
    await user.type(screen.getByLabelText(/limit price/i), "320.00");

    expect(screen.getByText(/time-in-force: FOK \(intrinsic to FOK\)/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(onSubmitted).toHaveBeenCalled();
    });
    expect(submittedBody).toHaveBeenCalledWith(
      expect.objectContaining({
        orderType: "FOK",
        tif: "FOK",
      }),
    );
  });
});
