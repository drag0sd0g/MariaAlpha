import { render, screen, waitFor } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import IcebergProgressBadge from "./IcebergProgressBadge";
import { server } from "@/test/mockServer";

describe("IcebergProgressBadge", () => {
  it("renders nothing when registry returns 404", () => {
    server.use(
      http.get(
        "/api/execution/orders/:id/iceberg-progress",
        () => new HttpResponse(null, { status: 404 }),
      ),
    );
    const { container } = render(<IcebergProgressBadge parentOrderId="missing" pollMs={5000} />);
    expect(container.firstChild).toBeNull();
  });

  it("renders filled / total counts and percentage", async () => {
    server.use(
      http.get("/api/execution/orders/:id/iceberg-progress", () =>
        HttpResponse.json({
          parentOrderId: "p-1",
          totalQuantity: 10000,
          displayQuantity: 1000,
          submittedQuantity: 3000,
          filledQuantity: 2500,
          slicesSubmitted: 3,
          activeChildOrderId: "c-3",
        }),
      ),
    );
    render(<IcebergProgressBadge parentOrderId="p-1" pollMs={5000} />);

    await waitFor(() => {
      expect(screen.getByText(/2,500 \/ 10,000/)).toBeInTheDocument();
    });
    expect(screen.getByText(/\(25%\)/)).toBeInTheDocument();
  });

  it("shows slice count in tooltip", async () => {
    server.use(
      http.get("/api/execution/orders/:id/iceberg-progress", () =>
        HttpResponse.json({
          parentOrderId: "p-1",
          totalQuantity: 6000,
          displayQuantity: 1000,
          submittedQuantity: 2000,
          filledQuantity: 1000,
          slicesSubmitted: 2,
          activeChildOrderId: "c-2",
        }),
      ),
    );
    render(<IcebergProgressBadge parentOrderId="p-1" pollMs={5000} />);

    const badge = await screen.findByTitle(/2 slices submitted/i);
    expect(badge).toBeInTheDocument();
  });
});
